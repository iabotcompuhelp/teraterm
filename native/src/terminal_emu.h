#ifndef OPENTERMX_TERMINAL_EMU_H
#define OPENTERMX_TERMINAL_EMU_H

#include <stddef.h>
#include <stdint.h>

#include "serial_native.h"   /* reusa OPENTERMX_API / OPENTERMX_CALL */

#ifdef __cplusplus
extern "C" {
#endif

/* ---------- Atributos de celda (bitmask) ------------------------------ */
#define OPENTERMX_ATTR_BOLD          (1u << 0)
#define OPENTERMX_ATTR_DIM           (1u << 1)
#define OPENTERMX_ATTR_ITALIC        (1u << 2)
#define OPENTERMX_ATTR_UNDERLINE     (1u << 3)
#define OPENTERMX_ATTR_BLINK         (1u << 4)
#define OPENTERMX_ATTR_REVERSE       (1u << 5)
#define OPENTERMX_ATTR_INVISIBLE     (1u << 6)
#define OPENTERMX_ATTR_STRIKETHROUGH (1u << 7)

/* Colores especiales */
#define OPENTERMX_COLOR_DEFAULT  0xFFFFFFFFu

/* ---------- Estructuras públicas (compatibles con JNA Structure) ------ */
typedef struct {
    uint32_t codepoint;   /* carácter Unicode (UCS-4) */
    uint32_t fg_rgb;      /* 0xRRGGBB o OPENTERMX_COLOR_DEFAULT */
    uint32_t bg_rgb;      /* 0xRRGGBB o OPENTERMX_COLOR_DEFAULT */
    uint16_t attrs;       /* bitmask OPENTERMX_ATTR_* */
    uint16_t dirty;       /* 1 si cambió desde el último volcado */
} opentermx_cell_t;

typedef struct {
    int32_t  row;
    int32_t  col;
    uint8_t  visible;
    uint8_t  blink;
    uint8_t  shape;       /* 0=block, 1=underline, 2=bar */
    uint8_t  _reserved;
} opentermx_cursor_t;

/* ---------- Tipo opaco ------------------------------------------------ */
typedef struct opentermx_term opentermx_term_t;

/* Callback opcional: la emulación devuelve datos al host (p. ej. respuestas DA, OSC). */
typedef void (OPENTERMX_CALL *opentermx_write_cb)(const uint8_t *data, size_t length, void *user);

/* ---------- API ------------------------------------------------------- */

/**
 * Crea un emulador con un grid de @p rows x @p cols.
 */
OPENTERMX_API opentermx_term_t *OPENTERMX_CALL opentermx_term_create(int32_t rows, int32_t cols);

/** Libera el emulador. Seguro pasar NULL. */
OPENTERMX_API void OPENTERMX_CALL opentermx_term_destroy(opentermx_term_t *term);

/** Redimensiona el grid. Preserva contenido en la medida de lo posible. */
OPENTERMX_API int OPENTERMX_CALL opentermx_term_resize(
        opentermx_term_t *term, int32_t rows, int32_t cols);

/** Resetea (RIS) el emulador. */
OPENTERMX_API void OPENTERMX_CALL opentermx_term_reset(opentermx_term_t *term);

/**
 * Alimenta el emulador con un bloque de bytes UTF-8 / VT.
 * @return número de bytes consumidos (siempre length, salvo errores).
 */
OPENTERMX_API size_t OPENTERMX_CALL opentermx_term_feed(
        opentermx_term_t *term, const uint8_t *data, size_t length);

/** Dimensiones actuales. */
OPENTERMX_API int32_t OPENTERMX_CALL opentermx_term_rows(const opentermx_term_t *term);
OPENTERMX_API int32_t OPENTERMX_CALL opentermx_term_cols(const opentermx_term_t *term);

/** Cursor actual. */
OPENTERMX_API void OPENTERMX_CALL opentermx_term_get_cursor(
        const opentermx_term_t *term, opentermx_cursor_t *out_cursor);

/**
 * Copia toda la pantalla en @p out_cells, que debe tener tamaño rows*cols.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_term_snapshot(
        const opentermx_term_t *term, opentermx_cell_t *out_cells, size_t cell_count);

/**
 * Copia una sola fila (0..rows-1).
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_term_get_row(
        const opentermx_term_t *term, int32_t row,
        opentermx_cell_t *out_cells, size_t cell_count);

/**
 * Devuelve hasta @p max_rows IDs de filas modificadas desde la última llamada
 * y limpia sus banderas dirty. @return número de filas devueltas.
 */
OPENTERMX_API size_t OPENTERMX_CALL opentermx_term_take_dirty_rows(
        opentermx_term_t *term, int32_t *out_rows, size_t max_rows);

/**
 * Registra un callback de escritura (host bound). Útil para respuestas DA/cursor.
 */
OPENTERMX_API void OPENTERMX_CALL opentermx_term_set_write_callback(
        opentermx_term_t *term, opentermx_write_cb cb, void *user);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* OPENTERMX_TERMINAL_EMU_H */