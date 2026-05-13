#include "terminal_emu.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* --------------------------------------------------------------------- */
/* Constantes internas                                                    */
/* --------------------------------------------------------------------- */
#define MAX_PARAMS    16
#define MAX_INTERMED  4
#define OSC_BUF_SIZE  512

#define DEFAULT_FG  0xC0C0C0u
#define DEFAULT_BG  0x000000u

/* Paleta ANSI 16 estándar (xterm) */
static const uint32_t k_ansi16[16] = {
    0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
    0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
};

/* --------------------------------------------------------------------- */
/* Estados de la máquina VT                                               */
/* --------------------------------------------------------------------- */
typedef enum {
    ST_GROUND = 0,
    ST_ESC,
    ST_CSI_ENTRY,
    ST_CSI_PARAM,
    ST_CSI_INTERMED,
    ST_OSC,
    ST_UTF8
} vt_state_t;

/* --------------------------------------------------------------------- */
/* Atributos de "pen" actual                                              */
/* --------------------------------------------------------------------- */
typedef struct {
    uint32_t fg_rgb;
    uint32_t bg_rgb;
    uint16_t attrs;
} pen_t;

struct opentermx_term {
    int32_t  rows;
    int32_t  cols;
    opentermx_cell_t *cells;       /* fila-mayor: cells[r*cols + c] */
    uint8_t          *row_dirty;   /* 1 por fila modificada */

    int32_t  cur_row;
    int32_t  cur_col;
    int32_t  saved_row;
    int32_t  saved_col;
    uint8_t  cursor_visible;

    pen_t    pen;
    pen_t    saved_pen;

    /* Scrolling region (0-based, inclusivo) */
    int32_t  scroll_top;
    int32_t  scroll_bot;

    /* Wrap pendiente: al imprimir en última columna no envolvemos hasta el siguiente char. */
    uint8_t  wrap_pending;
    uint8_t  autowrap;

    /* Máquina de estados */
    vt_state_t state;
    int32_t  params[MAX_PARAMS];
    int32_t  param_count;
    uint8_t  param_has_value;
    char     intermed[MAX_INTERMED];
    int32_t  intermed_count;
    char     csi_prefix;   /* '?', '>' o 0 */

    /* OSC buffer */
    char     osc_buf[OSC_BUF_SIZE];
    int32_t  osc_len;

    /* Decodificador UTF-8 */
    uint32_t utf8_cp;
    int32_t  utf8_remaining;

    /* Callback host */
    opentermx_write_cb write_cb;
    void              *write_user;
};

/* --------------------------------------------------------------------- */
/* Utilidades                                                             */
/* --------------------------------------------------------------------- */
static inline opentermx_cell_t *cell_at(opentermx_term_t *t, int32_t r, int32_t c) {
    return &t->cells[(size_t)r * (size_t)t->cols + (size_t)c];
}

static void mark_dirty(opentermx_term_t *t, int32_t r) {
    if (r >= 0 && r < t->rows) t->row_dirty[r] = 1;
}

static void clear_cell(opentermx_cell_t *cell, const pen_t *pen) {
    cell->codepoint = ' ';
    cell->fg_rgb    = pen->fg_rgb;
    cell->bg_rgb    = pen->bg_rgb;
    cell->attrs     = pen->attrs;
    cell->dirty     = 1;
}

static void clear_row(opentermx_term_t *t, int32_t r, int32_t from_col, int32_t to_col) {
    if (r < 0 || r >= t->rows) return;
    if (from_col < 0) from_col = 0;
    if (to_col >= t->cols) to_col = t->cols - 1;
    for (int32_t c = from_col; c <= to_col; c++) {
        clear_cell(cell_at(t, r, c), &t->pen);
    }
    mark_dirty(t, r);
}

static void scroll_up(opentermx_term_t *t, int32_t n) {
    if (n <= 0) return;
    int32_t top = t->scroll_top;
    int32_t bot = t->scroll_bot;
    if (n > bot - top + 1) n = bot - top + 1;

    /* Desplaza filas hacia arriba */
    for (int32_t r = top; r + n <= bot; r++) {
        memcpy(cell_at(t, r, 0), cell_at(t, r + n, 0),
               (size_t)t->cols * sizeof(opentermx_cell_t));
        mark_dirty(t, r);
    }
    /* Limpia las últimas n filas */
    for (int32_t r = bot - n + 1; r <= bot; r++) {
        clear_row(t, r, 0, t->cols - 1);
    }
}

static void scroll_down(opentermx_term_t *t, int32_t n) {
    if (n <= 0) return;
    int32_t top = t->scroll_top;
    int32_t bot = t->scroll_bot;
    if (n > bot - top + 1) n = bot - top + 1;

    for (int32_t r = bot; r - n >= top; r--) {
        memcpy(cell_at(t, r, 0), cell_at(t, r - n, 0),
               (size_t)t->cols * sizeof(opentermx_cell_t));
        mark_dirty(t, r);
    }
    for (int32_t r = top; r < top + n; r++) {
        clear_row(t, r, 0, t->cols - 1);
    }
}

static void linefeed(opentermx_term_t *t) {
    if (t->cur_row == t->scroll_bot) {
        scroll_up(t, 1);
    } else if (t->cur_row < t->rows - 1) {
        t->cur_row++;
    }
}

static void put_char(opentermx_term_t *t, uint32_t cp) {
    if (t->wrap_pending && t->autowrap) {
        t->cur_col = 0;
        linefeed(t);
        t->wrap_pending = 0;
    }
    if (t->cur_col >= t->cols) t->cur_col = t->cols - 1;

    opentermx_cell_t *cell = cell_at(t, t->cur_row, t->cur_col);
    cell->codepoint = cp;
    cell->fg_rgb    = t->pen.fg_rgb;
    cell->bg_rgb    = t->pen.bg_rgb;
    cell->attrs     = t->pen.attrs;
    cell->dirty     = 1;
    mark_dirty(t, t->cur_row);

    if (t->cur_col + 1 >= t->cols) {
        t->wrap_pending = 1;
    } else {
        t->cur_col++;
    }
}

/* --------------------------------------------------------------------- */
/* Parámetros CSI                                                         */
/* --------------------------------------------------------------------- */
static void reset_params(opentermx_term_t *t) {
    for (int i = 0; i < MAX_PARAMS; i++) t->params[i] = 0;
    t->param_count = 0;
    t->param_has_value = 0;
    t->intermed_count = 0;
    t->csi_prefix = 0;
}

static int32_t param_or(const opentermx_term_t *t, int idx, int32_t def) {
    if (idx >= t->param_count) return def;
    return (t->params[idx] == 0 && !t->param_has_value) ? def : t->params[idx];
}

/* --------------------------------------------------------------------- */
/* SGR (Select Graphic Rendition)                                         */
/* --------------------------------------------------------------------- */
static uint32_t sgr_indexed_color(int32_t idx) {
    if (idx < 16) return k_ansi16[idx];
    if (idx < 232) {
        int32_t i = idx - 16;
        int32_t r = (i / 36) % 6;
        int32_t g = (i / 6) % 6;
        int32_t b = i % 6;
        static const uint8_t lvl[6] = {0, 0x5F, 0x87, 0xAF, 0xD7, 0xFF};
        return ((uint32_t)lvl[r] << 16) | ((uint32_t)lvl[g] << 8) | (uint32_t)lvl[b];
    }
    if (idx < 256) {
        uint8_t v = (uint8_t)(8 + (idx - 232) * 10);
        return ((uint32_t)v << 16) | ((uint32_t)v << 8) | (uint32_t)v;
    }
    return DEFAULT_FG;
}

static void apply_sgr(opentermx_term_t *t) {
    if (t->param_count == 0) {
        /* CSI m == CSI 0 m */
        t->pen.attrs  = 0;
        t->pen.fg_rgb = DEFAULT_FG;
        t->pen.bg_rgb = DEFAULT_BG;
        return;
    }
    for (int i = 0; i < t->param_count; i++) {
        int32_t p = t->params[i];
        switch (p) {
            case 0:  t->pen.attrs = 0; t->pen.fg_rgb = DEFAULT_FG; t->pen.bg_rgb = DEFAULT_BG; break;
            case 1:  t->pen.attrs |= OPENTERMX_ATTR_BOLD; break;
            case 2:  t->pen.attrs |= OPENTERMX_ATTR_DIM; break;
            case 3:  t->pen.attrs |= OPENTERMX_ATTR_ITALIC; break;
            case 4:  t->pen.attrs |= OPENTERMX_ATTR_UNDERLINE; break;
            case 5:  t->pen.attrs |= OPENTERMX_ATTR_BLINK; break;
            case 7:  t->pen.attrs |= OPENTERMX_ATTR_REVERSE; break;
            case 8:  t->pen.attrs |= OPENTERMX_ATTR_INVISIBLE; break;
            case 9:  t->pen.attrs |= OPENTERMX_ATTR_STRIKETHROUGH; break;
            case 22: t->pen.attrs &= ~(OPENTERMX_ATTR_BOLD | OPENTERMX_ATTR_DIM); break;
            case 23: t->pen.attrs &= ~OPENTERMX_ATTR_ITALIC; break;
            case 24: t->pen.attrs &= ~OPENTERMX_ATTR_UNDERLINE; break;
            case 25: t->pen.attrs &= ~OPENTERMX_ATTR_BLINK; break;
            case 27: t->pen.attrs &= ~OPENTERMX_ATTR_REVERSE; break;
            case 28: t->pen.attrs &= ~OPENTERMX_ATTR_INVISIBLE; break;
            case 29: t->pen.attrs &= ~OPENTERMX_ATTR_STRIKETHROUGH; break;

            case 30: case 31: case 32: case 33:
            case 34: case 35: case 36: case 37:
                t->pen.fg_rgb = k_ansi16[p - 30]; break;
            case 39:
                t->pen.fg_rgb = DEFAULT_FG; break;
            case 40: case 41: case 42: case 43:
            case 44: case 45: case 46: case 47:
                t->pen.bg_rgb = k_ansi16[p - 40]; break;
            case 49:
                t->pen.bg_rgb = DEFAULT_BG; break;
            case 90: case 91: case 92: case 93:
            case 94: case 95: case 96: case 97:
                t->pen.fg_rgb = k_ansi16[p - 90 + 8]; break;
            case 100: case 101: case 102: case 103:
            case 104: case 105: case 106: case 107:
                t->pen.bg_rgb = k_ansi16[p - 100 + 8]; break;

            case 38: /* fg extendido */
                if (i + 2 < t->param_count && t->params[i + 1] == 5) {
                    t->pen.fg_rgb = sgr_indexed_color(t->params[i + 2]);
                    i += 2;
                } else if (i + 4 < t->param_count && t->params[i + 1] == 2) {
                    t->pen.fg_rgb = ((uint32_t)(t->params[i + 2] & 0xFF) << 16)
                                  | ((uint32_t)(t->params[i + 3] & 0xFF) << 8)
                                  |  (uint32_t)(t->params[i + 4] & 0xFF);
                    i += 4;
                }
                break;
            case 48: /* bg extendido */
                if (i + 2 < t->param_count && t->params[i + 1] == 5) {
                    t->pen.bg_rgb = sgr_indexed_color(t->params[i + 2]);
                    i += 2;
                } else if (i + 4 < t->param_count && t->params[i + 1] == 2) {
                    t->pen.bg_rgb = ((uint32_t)(t->params[i + 2] & 0xFF) << 16)
                                  | ((uint32_t)(t->params[i + 3] & 0xFF) << 8)
                                  |  (uint32_t)(t->params[i + 4] & 0xFF);
                    i += 4;
                }
                break;
            default: break;
        }
    }
}

/* --------------------------------------------------------------------- */
/* Dispatch CSI                                                           */
/* --------------------------------------------------------------------- */
static void clamp_cursor(opentermx_term_t *t) {
    if (t->cur_row < 0) t->cur_row = 0;
    if (t->cur_row >= t->rows) t->cur_row = t->rows - 1;
    if (t->cur_col < 0) t->cur_col = 0;
    if (t->cur_col >= t->cols) t->cur_col = t->cols - 1;
    t->wrap_pending = 0;
}

static void csi_dispatch(opentermx_term_t *t, char final) {
    int32_t n;
    switch (final) {
        case '@': /* ICH: insert n blank chars */
            n = param_or(t, 0, 1);
            if (n > t->cols - t->cur_col) n = t->cols - t->cur_col;
            for (int32_t c = t->cols - 1; c >= t->cur_col + n; c--) {
                *cell_at(t, t->cur_row, c) = *cell_at(t, t->cur_row, c - n);
            }
            for (int32_t c = t->cur_col; c < t->cur_col + n; c++) {
                clear_cell(cell_at(t, t->cur_row, c), &t->pen);
            }
            mark_dirty(t, t->cur_row);
            break;
        case 'A': /* CUU */
            t->cur_row -= param_or(t, 0, 1); clamp_cursor(t); break;
        case 'B': /* CUD */
            t->cur_row += param_or(t, 0, 1); clamp_cursor(t); break;
        case 'C': /* CUF */
            t->cur_col += param_or(t, 0, 1); clamp_cursor(t); break;
        case 'D': /* CUB */
            t->cur_col -= param_or(t, 0, 1); clamp_cursor(t); break;
        case 'E': /* CNL */
            t->cur_row += param_or(t, 0, 1); t->cur_col = 0; clamp_cursor(t); break;
        case 'F': /* CPL */
            t->cur_row -= param_or(t, 0, 1); t->cur_col = 0; clamp_cursor(t); break;
        case 'G': /* CHA: cursor to column */
            t->cur_col = param_or(t, 0, 1) - 1; clamp_cursor(t); break;
        case 'H': case 'f': /* CUP / HVP */
            t->cur_row = param_or(t, 0, 1) - 1;
            t->cur_col = param_or(t, 1, 1) - 1;
            clamp_cursor(t);
            break;
        case 'J': { /* ED */
            int32_t mode = param_or(t, 0, 0);
            if (mode == 0) {
                clear_row(t, t->cur_row, t->cur_col, t->cols - 1);
                for (int32_t r = t->cur_row + 1; r < t->rows; r++) clear_row(t, r, 0, t->cols - 1);
            } else if (mode == 1) {
                clear_row(t, t->cur_row, 0, t->cur_col);
                for (int32_t r = 0; r < t->cur_row; r++) clear_row(t, r, 0, t->cols - 1);
            } else if (mode == 2 || mode == 3) {
                for (int32_t r = 0; r < t->rows; r++) clear_row(t, r, 0, t->cols - 1);
            }
            break;
        }
        case 'K': { /* EL */
            int32_t mode = param_or(t, 0, 0);
            if (mode == 0)      clear_row(t, t->cur_row, t->cur_col, t->cols - 1);
            else if (mode == 1) clear_row(t, t->cur_row, 0, t->cur_col);
            else if (mode == 2) clear_row(t, t->cur_row, 0, t->cols - 1);
            break;
        }
        case 'L': /* IL: insert n lines */
            if (t->cur_row >= t->scroll_top && t->cur_row <= t->scroll_bot) {
                int32_t saved_top = t->scroll_top;
                t->scroll_top = t->cur_row;
                scroll_down(t, param_or(t, 0, 1));
                t->scroll_top = saved_top;
            }
            break;
        case 'M': /* DL: delete n lines */
            if (t->cur_row >= t->scroll_top && t->cur_row <= t->scroll_bot) {
                int32_t saved_top = t->scroll_top;
                t->scroll_top = t->cur_row;
                scroll_up(t, param_or(t, 0, 1));
                t->scroll_top = saved_top;
            }
            break;
        case 'P': /* DCH: delete n chars */
            n = param_or(t, 0, 1);
            if (n > t->cols - t->cur_col) n = t->cols - t->cur_col;
            for (int32_t c = t->cur_col; c + n < t->cols; c++) {
                *cell_at(t, t->cur_row, c) = *cell_at(t, t->cur_row, c + n);
            }
            for (int32_t c = t->cols - n; c < t->cols; c++) {
                clear_cell(cell_at(t, t->cur_row, c), &t->pen);
            }
            mark_dirty(t, t->cur_row);
            break;
        case 'S': scroll_up(t, param_or(t, 0, 1)); break;
        case 'T': scroll_down(t, param_or(t, 0, 1)); break;
        case 'X': { /* ECH */
            int32_t cnt = param_or(t, 0, 1);
            int32_t end = t->cur_col + cnt - 1;
            if (end >= t->cols) end = t->cols - 1;
            clear_row(t, t->cur_row, t->cur_col, end);
            break;
        }
        case 'd': /* VPA */
            t->cur_row = param_or(t, 0, 1) - 1; clamp_cursor(t); break;
        case 'h': /* SM (modos) */
            if (t->csi_prefix == '?') {
                for (int i = 0; i < t->param_count; i++) {
                    if (t->params[i] == 7)  t->autowrap = 1;
                    else if (t->params[i] == 25) t->cursor_visible = 1;
                }
            }
            break;
        case 'l': /* RM (modos) */
            if (t->csi_prefix == '?') {
                for (int i = 0; i < t->param_count; i++) {
                    if (t->params[i] == 7)  t->autowrap = 0;
                    else if (t->params[i] == 25) t->cursor_visible = 0;
                }
            }
            break;
        case 'm': apply_sgr(t); break;
        case 'n': /* DSR */
            if (t->write_cb && param_or(t, 0, 0) == 6) {
                char resp[32];
                int len = snprintf(resp, sizeof(resp), "\x1b[%d;%dR",
                                   t->cur_row + 1, t->cur_col + 1);
                if (len > 0) t->write_cb((const uint8_t *)resp, (size_t)len, t->write_user);
            }
            break;
        case 'r': { /* DECSTBM */
            int32_t top = param_or(t, 0, 1) - 1;
            int32_t bot = param_or(t, 1, t->rows) - 1;
            if (top < 0) top = 0;
            if (bot >= t->rows) bot = t->rows - 1;
            if (top < bot) {
                t->scroll_top = top;
                t->scroll_bot = bot;
                t->cur_row = top;
                t->cur_col = 0;
            }
            break;
        }
        case 's': t->saved_row = t->cur_row; t->saved_col = t->cur_col; break;
        case 'u': t->cur_row = t->saved_row; t->cur_col = t->saved_col; clamp_cursor(t); break;
        case 'c': /* DA */
            if (t->write_cb) {
                static const char resp[] = "\x1b[?6c"; /* VT102 */
                t->write_cb((const uint8_t *)resp, sizeof(resp) - 1, t->write_user);
            }
            break;
        default: break;
    }
}

/* --------------------------------------------------------------------- */
/* Manejo de un byte                                                      */
/* --------------------------------------------------------------------- */
static void handle_c0(opentermx_term_t *t, uint8_t b) {
    switch (b) {
        case 0x07: /* BEL */ break;
        case 0x08: /* BS */
            if (t->cur_col > 0) t->cur_col--;
            t->wrap_pending = 0;
            break;
        case 0x09: /* HT */
            t->cur_col = ((t->cur_col / 8) + 1) * 8;
            if (t->cur_col >= t->cols) t->cur_col = t->cols - 1;
            t->wrap_pending = 0;
            break;
        case 0x0A: /* LF */
        case 0x0B: /* VT */
        case 0x0C: /* FF */
            linefeed(t);
            t->wrap_pending = 0;
            break;
        case 0x0D: /* CR */
            t->cur_col = 0;
            t->wrap_pending = 0;
            break;
        default: break;
    }
}

static void feed_byte(opentermx_term_t *t, uint8_t b) {
    /* Cancela secuencias en curso ante CAN/SUB */
    if (b == 0x18 || b == 0x1A) {
        t->state = ST_GROUND;
        return;
    }
    if (b == 0x1B) {
        t->state = ST_ESC;
        reset_params(t);
        return;
    }

    switch (t->state) {
        case ST_GROUND:
            if (b < 0x20) { handle_c0(t, b); return; }
            if (b < 0x80) { put_char(t, b); return; }
            /* UTF-8 inicio */
            if ((b & 0xE0) == 0xC0)      { t->utf8_cp = b & 0x1F; t->utf8_remaining = 1; t->state = ST_UTF8; }
            else if ((b & 0xF0) == 0xE0) { t->utf8_cp = b & 0x0F; t->utf8_remaining = 2; t->state = ST_UTF8; }
            else if ((b & 0xF8) == 0xF0) { t->utf8_cp = b & 0x07; t->utf8_remaining = 3; t->state = ST_UTF8; }
            else                          { put_char(t, 0xFFFD); }
            return;

        case ST_UTF8:
            if ((b & 0xC0) != 0x80) {
                /* secuencia inválida */
                put_char(t, 0xFFFD);
                t->state = ST_GROUND;
                feed_byte(t, b);
                return;
            }
            t->utf8_cp = (t->utf8_cp << 6) | (b & 0x3F);
            if (--t->utf8_remaining == 0) {
                put_char(t, t->utf8_cp);
                t->state = ST_GROUND;
            }
            return;

        case ST_ESC:
            switch (b) {
                case '[': t->state = ST_CSI_ENTRY; reset_params(t); return;
                case ']': t->osc_len = 0; t->state = ST_OSC; return;
                case 'D': linefeed(t); t->state = ST_GROUND; return;             /* IND */
                case 'E': linefeed(t); t->cur_col = 0; t->state = ST_GROUND; return; /* NEL */
                case 'M': /* RI */
                    if (t->cur_row == t->scroll_top) scroll_down(t, 1);
                    else if (t->cur_row > 0) t->cur_row--;
                    t->state = ST_GROUND; return;
                case '7': t->saved_row = t->cur_row; t->saved_col = t->cur_col;
                          t->saved_pen = t->pen; t->state = ST_GROUND; return;
                case '8': t->cur_row = t->saved_row; t->cur_col = t->saved_col;
                          t->pen = t->saved_pen; t->state = ST_GROUND; return;
                case 'c': opentermx_term_reset(t); return;
                default:  t->state = ST_GROUND; return;
            }

        case ST_CSI_ENTRY:
            if (b == '?' || b == '>' || b == '<' || b == '=') {
                t->csi_prefix = (char)b;
                t->state = ST_CSI_PARAM;
                return;
            }
            t->state = ST_CSI_PARAM;
            /* fallthrough */
        case ST_CSI_PARAM:
            if (b >= '0' && b <= '9') {
                if (t->param_count == 0) t->param_count = 1;
                int32_t *p = &t->params[t->param_count - 1];
                if (t->param_count <= MAX_PARAMS) {
                    *p = (*p * 10) + (b - '0');
                    t->param_has_value = 1;
                }
                return;
            }
            if (b == ';') {
                if (t->param_count < MAX_PARAMS) {
                    t->param_count++;
                    t->params[t->param_count - 1] = 0;
                }
                return;
            }
            if (b >= 0x20 && b <= 0x2F) {
                if (t->intermed_count < MAX_INTERMED) {
                    t->intermed[t->intermed_count++] = (char)b;
                }
                t->state = ST_CSI_INTERMED;
                return;
            }
            if (b >= 0x40 && b <= 0x7E) {
                csi_dispatch(t, (char)b);
                t->state = ST_GROUND;
                return;
            }
            t->state = ST_GROUND;
            return;

        case ST_CSI_INTERMED:
            if (b >= 0x20 && b <= 0x2F) {
                if (t->intermed_count < MAX_INTERMED) {
                    t->intermed[t->intermed_count++] = (char)b;
                }
                return;
            }
            if (b >= 0x40 && b <= 0x7E) {
                csi_dispatch(t, (char)b);
            }
            t->state = ST_GROUND;
            return;

        case ST_OSC:
            if (b == 0x07 /* BEL */) {
                t->state = ST_GROUND;
                t->osc_len = 0;
                return;
            }
            if (t->osc_len < OSC_BUF_SIZE - 1) {
                t->osc_buf[t->osc_len++] = (char)b;
            }
            return;
    }
}

/* --------------------------------------------------------------------- */
/* API pública                                                            */
/* --------------------------------------------------------------------- */
OPENTERMX_API opentermx_term_t *OPENTERMX_CALL opentermx_term_create(int32_t rows, int32_t cols) {
    if (rows <= 0 || cols <= 0) return NULL;
    opentermx_term_t *t = (opentermx_term_t *)calloc(1, sizeof(*t));
    if (!t) return NULL;
    t->rows = rows;
    t->cols = cols;
    t->cells = (opentermx_cell_t *)calloc((size_t)rows * (size_t)cols, sizeof(opentermx_cell_t));
    t->row_dirty = (uint8_t *)calloc((size_t)rows, sizeof(uint8_t));
    if (!t->cells || !t->row_dirty) {
        free(t->cells);
        free(t->row_dirty);
        free(t);
        return NULL;
    }
    opentermx_term_reset(t);
    return t;
}

OPENTERMX_API void OPENTERMX_CALL opentermx_term_destroy(opentermx_term_t *t) {
    if (!t) return;
    free(t->cells);
    free(t->row_dirty);
    free(t);
}

OPENTERMX_API void OPENTERMX_CALL opentermx_term_reset(opentermx_term_t *t) {
    if (!t) return;
    t->pen.fg_rgb = DEFAULT_FG;
    t->pen.bg_rgb = DEFAULT_BG;
    t->pen.attrs  = 0;
    t->saved_pen  = t->pen;
    t->cur_row = t->cur_col = 0;
    t->saved_row = t->saved_col = 0;
    t->cursor_visible = 1;
    t->scroll_top = 0;
    t->scroll_bot = t->rows - 1;
    t->wrap_pending = 0;
    t->autowrap = 1;
    t->state = ST_GROUND;
    t->utf8_cp = 0;
    t->utf8_remaining = 0;
    t->osc_len = 0;
    reset_params(t);

    for (int32_t r = 0; r < t->rows; r++) {
        clear_row(t, r, 0, t->cols - 1);
    }
}

OPENTERMX_API int OPENTERMX_CALL opentermx_term_resize(
        opentermx_term_t *t, int32_t rows, int32_t cols) {
    if (!t || rows <= 0 || cols <= 0) return OPENTERMX_ERR_INVALID;
    if (rows == t->rows && cols == t->cols) return OPENTERMX_OK;

    opentermx_cell_t *nc = (opentermx_cell_t *)calloc((size_t)rows * (size_t)cols, sizeof(opentermx_cell_t));
    uint8_t *nd = (uint8_t *)calloc((size_t)rows, sizeof(uint8_t));
    if (!nc || !nd) { free(nc); free(nd); return OPENTERMX_ERR_IO; }

    /* Copia preservando esquina superior */
    int32_t copy_rows = (rows < t->rows) ? rows : t->rows;
    int32_t copy_cols = (cols < t->cols) ? cols : t->cols;
    for (int32_t r = 0; r < copy_rows; r++) {
        memcpy(&nc[(size_t)r * (size_t)cols],
               &t->cells[(size_t)r * (size_t)t->cols],
               (size_t)copy_cols * sizeof(opentermx_cell_t));
        nd[r] = 1;
    }
    /* Inicializa el resto con espacios usando el pen actual */
    for (int32_t r = 0; r < rows; r++) {
        int32_t start = (r < copy_rows) ? copy_cols : 0;
        for (int32_t c = start; c < cols; c++) {
            opentermx_cell_t *cell = &nc[(size_t)r * (size_t)cols + (size_t)c];
            cell->codepoint = ' ';
            cell->fg_rgb    = t->pen.fg_rgb;
            cell->bg_rgb    = t->pen.bg_rgb;
            cell->attrs     = t->pen.attrs;
            cell->dirty     = 1;
        }
        nd[r] = 1;
    }

    free(t->cells);
    free(t->row_dirty);
    t->cells = nc;
    t->row_dirty = nd;
    t->rows = rows;
    t->cols = cols;
    t->scroll_top = 0;
    t->scroll_bot = rows - 1;
    clamp_cursor(t);
    return OPENTERMX_OK;
}

OPENTERMX_API size_t OPENTERMX_CALL opentermx_term_feed(
        opentermx_term_t *t, const uint8_t *data, size_t length) {
    if (!t || !data) return 0;
    for (size_t i = 0; i < length; i++) feed_byte(t, data[i]);
    return length;
}

OPENTERMX_API int32_t OPENTERMX_CALL opentermx_term_rows(const opentermx_term_t *t) {
    return t ? t->rows : 0;
}

OPENTERMX_API int32_t OPENTERMX_CALL opentermx_term_cols(const opentermx_term_t *t) {
    return t ? t->cols : 0;
}

OPENTERMX_API void OPENTERMX_CALL opentermx_term_get_cursor(
        const opentermx_term_t *t, opentermx_cursor_t *out) {
    if (!t || !out) return;
    out->row     = t->cur_row;
    out->col     = t->cur_col;
    out->visible = t->cursor_visible;
    out->blink   = 1;
    out->shape   = 0;
    out->_reserved = 0;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_term_snapshot(
        const opentermx_term_t *t, opentermx_cell_t *out, size_t cell_count) {
    if (!t || !out) return OPENTERMX_ERR_INVALID;
    size_t need = (size_t)t->rows * (size_t)t->cols;
    if (cell_count < need) return OPENTERMX_ERR_INVALID;
    memcpy(out, t->cells, need * sizeof(opentermx_cell_t));
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_term_get_row(
        const opentermx_term_t *t, int32_t row, opentermx_cell_t *out, size_t cell_count) {
    if (!t || !out) return OPENTERMX_ERR_INVALID;
    if (row < 0 || row >= t->rows) return OPENTERMX_ERR_INVALID;
    if (cell_count < (size_t)t->cols) return OPENTERMX_ERR_INVALID;
    memcpy(out, &t->cells[(size_t)row * (size_t)t->cols],
           (size_t)t->cols * sizeof(opentermx_cell_t));
    return OPENTERMX_OK;
}

OPENTERMX_API size_t OPENTERMX_CALL opentermx_term_take_dirty_rows(
        opentermx_term_t *t, int32_t *out_rows, size_t max_rows) {
    if (!t || !out_rows || max_rows == 0) return 0;
    size_t count = 0;
    for (int32_t r = 0; r < t->rows && count < max_rows; r++) {
        if (t->row_dirty[r]) {
            out_rows[count++] = r;
            t->row_dirty[r] = 0;
        }
    }
    return count;
}

OPENTERMX_API void OPENTERMX_CALL opentermx_term_set_write_callback(
        opentermx_term_t *t, opentermx_write_cb cb, void *user) {
    if (!t) return;
    t->write_cb = cb;
    t->write_user = user;
}