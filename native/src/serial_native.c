#include "serial_native.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <errno.h>
#  include <fcntl.h>
#  include <poll.h>
#  include <sys/ioctl.h>
#  include <termios.h>
#  include <unistd.h>
#endif

#define OPENTERMX_VERSION_MAJOR 1
#define OPENTERMX_VERSION_MINOR 0
#define OPENTERMX_VERSION_PATCH 0

/* --------------------------------------------------------------------- */
/* Estructura opaca                                                       */
/* --------------------------------------------------------------------- */
struct opentermx_serial {
#if defined(_WIN32)
    HANDLE handle;
#else
    int    fd;
#endif
    opentermx_serial_config_t config;
};

OPENTERMX_API uint32_t OPENTERMX_CALL opentermx_version(void) {
    return ((uint32_t)OPENTERMX_VERSION_MAJOR << 16)
         | ((uint32_t)OPENTERMX_VERSION_MINOR << 8)
         |  (uint32_t)OPENTERMX_VERSION_PATCH;
}

OPENTERMX_API const char *OPENTERMX_CALL opentermx_strerror(int code) {
    switch (code) {
        case OPENTERMX_OK:             return "ok";
        case OPENTERMX_ERR_INVALID:    return "argumento invalido";
        case OPENTERMX_ERR_OPEN:       return "no se pudo abrir el puerto";
        case OPENTERMX_ERR_CONFIG:     return "fallo configurando el puerto";
        case OPENTERMX_ERR_IO:         return "error de E/S";
        case OPENTERMX_ERR_TIMEOUT:    return "timeout";
        case OPENTERMX_ERR_CLOSED:     return "puerto cerrado";
        case OPENTERMX_ERR_NOT_FOUND:  return "puerto no encontrado";
        default:                       return "error desconocido";
    }
}

/* ===================================================================== */
/* Backend Windows                                                        */
/* ===================================================================== */
#if defined(_WIN32)

static int apply_config_win(HANDLE h, const opentermx_serial_config_t *cfg) {
    DCB dcb;
    SecureZeroMemory(&dcb, sizeof(dcb));
    dcb.DCBlength = sizeof(dcb);
    if (!GetCommState(h, &dcb)) return OPENTERMX_ERR_CONFIG;

    dcb.BaudRate = (DWORD)cfg->baud_rate;
    dcb.ByteSize = (BYTE)cfg->data_bits;

    switch (cfg->stop_bits) {
        case OPENTERMX_STOP_ONE:      dcb.StopBits = ONESTOPBIT;   break;
        case OPENTERMX_STOP_ONE_HALF: dcb.StopBits = ONE5STOPBITS; break;
        case OPENTERMX_STOP_TWO:      dcb.StopBits = TWOSTOPBITS;  break;
        default: return OPENTERMX_ERR_INVALID;
    }

    switch (cfg->parity) {
        case OPENTERMX_PARITY_NONE:  dcb.fParity = FALSE; dcb.Parity = NOPARITY;    break;
        case OPENTERMX_PARITY_ODD:   dcb.fParity = TRUE;  dcb.Parity = ODDPARITY;   break;
        case OPENTERMX_PARITY_EVEN:  dcb.fParity = TRUE;  dcb.Parity = EVENPARITY;  break;
        case OPENTERMX_PARITY_MARK:  dcb.fParity = TRUE;  dcb.Parity = MARKPARITY;  break;
        case OPENTERMX_PARITY_SPACE: dcb.fParity = TRUE;  dcb.Parity = SPACEPARITY; break;
        default: return OPENTERMX_ERR_INVALID;
    }

    dcb.fBinary       = TRUE;
    dcb.fOutxCtsFlow  = FALSE;
    dcb.fOutxDsrFlow  = FALSE;
    dcb.fDsrSensitivity = FALSE;
    dcb.fDtrControl   = DTR_CONTROL_ENABLE;
    dcb.fRtsControl   = RTS_CONTROL_ENABLE;
    dcb.fOutX         = FALSE;
    dcb.fInX          = FALSE;
    dcb.fAbortOnError = FALSE;

    switch (cfg->flow_control) {
        case OPENTERMX_FLOW_NONE:
            break;
        case OPENTERMX_FLOW_RTSCTS:
            dcb.fOutxCtsFlow = TRUE;
            dcb.fRtsControl  = RTS_CONTROL_HANDSHAKE;
            break;
        case OPENTERMX_FLOW_XONXOFF:
            dcb.fOutX = TRUE;
            dcb.fInX  = TRUE;
            dcb.XonChar  = 0x11;
            dcb.XoffChar = 0x13;
            dcb.XonLim   = 2048;
            dcb.XoffLim  = 512;
            break;
        default: return OPENTERMX_ERR_INVALID;
    }

    if (!SetCommState(h, &dcb)) return OPENTERMX_ERR_CONFIG;

    COMMTIMEOUTS to;
    SecureZeroMemory(&to, sizeof(to));
    if (cfg->read_timeout_ms < 0) {
        /* lectura bloqueante */
        to.ReadIntervalTimeout         = 0;
        to.ReadTotalTimeoutConstant    = 0;
        to.ReadTotalTimeoutMultiplier  = 0;
    } else if (cfg->read_timeout_ms == 0) {
        /* no bloqueante: vuelve inmediatamente */
        to.ReadIntervalTimeout         = MAXDWORD;
        to.ReadTotalTimeoutConstant    = 0;
        to.ReadTotalTimeoutMultiplier  = 0;
    } else {
        to.ReadIntervalTimeout         = MAXDWORD;
        to.ReadTotalTimeoutConstant    = (DWORD)cfg->read_timeout_ms;
        to.ReadTotalTimeoutMultiplier  = MAXDWORD;
    }
    to.WriteTotalTimeoutConstant   = (cfg->write_timeout_ms > 0) ? (DWORD)cfg->write_timeout_ms : 0;
    to.WriteTotalTimeoutMultiplier = 0;
    if (!SetCommTimeouts(h, &to)) return OPENTERMX_ERR_CONFIG;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_open(
        const char *port_name,
        const opentermx_serial_config_t *config,
        opentermx_serial_t **out_handle) {

    if (!port_name || !config || !out_handle) return OPENTERMX_ERR_INVALID;
    *out_handle = NULL;

    /* Para puertos >= COM10 hace falta el prefijo \\.\ */
    char path[64];
    if (strncmp(port_name, "\\\\.\\", 4) == 0) {
        snprintf(path, sizeof(path), "%s", port_name);
    } else {
        snprintf(path, sizeof(path), "\\\\.\\%s", port_name);
    }

    HANDLE h = CreateFileA(path,
                           GENERIC_READ | GENERIC_WRITE,
                           0,
                           NULL,
                           OPEN_EXISTING,
                           0,
                           NULL);
    if (h == INVALID_HANDLE_VALUE) {
        DWORD err = GetLastError();
        return (err == ERROR_FILE_NOT_FOUND) ? OPENTERMX_ERR_NOT_FOUND : OPENTERMX_ERR_OPEN;
    }

    if (!SetupComm(h, 8192, 8192)) {
        CloseHandle(h);
        return OPENTERMX_ERR_CONFIG;
    }

    opentermx_serial_t *s = (opentermx_serial_t *)calloc(1, sizeof(*s));
    if (!s) {
        CloseHandle(h);
        return OPENTERMX_ERR_IO;
    }
    s->handle = h;
    s->config = *config;

    int rc = apply_config_win(h, config);
    if (rc != OPENTERMX_OK) {
        CloseHandle(h);
        free(s);
        return rc;
    }

    PurgeComm(h, PURGE_RXCLEAR | PURGE_TXCLEAR);
    *out_handle = s;
    return OPENTERMX_OK;
}

OPENTERMX_API void OPENTERMX_CALL opentermx_serial_close(opentermx_serial_t *handle) {
    if (!handle) return;
    if (handle->handle && handle->handle != INVALID_HANDLE_VALUE) {
        CloseHandle(handle->handle);
    }
    free(handle);
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_configure(
        opentermx_serial_t *handle, const opentermx_serial_config_t *config) {
    if (!handle || !config) return OPENTERMX_ERR_INVALID;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    int rc = apply_config_win(handle->handle, config);
    if (rc == OPENTERMX_OK) handle->config = *config;
    return rc;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_read(
        opentermx_serial_t *handle, uint8_t *buffer, size_t length, size_t *out_read) {
    if (!handle || !buffer || !out_read) return OPENTERMX_ERR_INVALID;
    *out_read = 0;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    if (length == 0) return OPENTERMX_OK;

    DWORD got = 0;
    if (!ReadFile(handle->handle, buffer, (DWORD)length, &got, NULL)) {
        return OPENTERMX_ERR_IO;
    }
    *out_read = (size_t)got;
    if (got == 0 && handle->config.read_timeout_ms > 0) return OPENTERMX_ERR_TIMEOUT;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_write(
        opentermx_serial_t *handle, const uint8_t *buffer, size_t length, size_t *out_written) {
    if (!handle || !buffer || !out_written) return OPENTERMX_ERR_INVALID;
    *out_written = 0;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    if (length == 0) return OPENTERMX_OK;

    DWORD wrote = 0;
    if (!WriteFile(handle->handle, buffer, (DWORD)length, &wrote, NULL)) {
        return OPENTERMX_ERR_IO;
    }
    *out_written = (size_t)wrote;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_flush(opentermx_serial_t *handle) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    if (!FlushFileBuffers(handle->handle)) return OPENTERMX_ERR_IO;
    if (!PurgeComm(handle->handle, PURGE_RXCLEAR | PURGE_TXCLEAR)) return OPENTERMX_ERR_IO;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_set_dtr(opentermx_serial_t *handle, int on) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    return EscapeCommFunction(handle->handle, on ? SETDTR : CLRDTR) ? OPENTERMX_OK : OPENTERMX_ERR_IO;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_set_rts(opentermx_serial_t *handle, int on) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    return EscapeCommFunction(handle->handle, on ? SETRTS : CLRRTS) ? OPENTERMX_OK : OPENTERMX_ERR_IO;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_send_break(
        opentermx_serial_t *handle, int32_t duration_ms) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    if (!SetCommBreak(handle->handle)) return OPENTERMX_ERR_IO;
    if (duration_ms > 0) Sleep((DWORD)duration_ms);
    if (!ClearCommBreak(handle->handle)) return OPENTERMX_ERR_IO;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_get_signals(
        opentermx_serial_t *handle, uint32_t *out_signals) {
    if (!handle || !out_signals) return OPENTERMX_ERR_INVALID;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    DWORD status = 0;
    if (!GetCommModemStatus(handle->handle, &status)) return OPENTERMX_ERR_IO;
    uint32_t s = 0;
    if (status & MS_CTS_ON)  s |= OPENTERMX_SIGNAL_CTS;
    if (status & MS_DSR_ON)  s |= OPENTERMX_SIGNAL_DSR;
    if (status & MS_RLSD_ON) s |= OPENTERMX_SIGNAL_DCD;
    if (status & MS_RING_ON) s |= OPENTERMX_SIGNAL_RI;
    *out_signals = s;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_available(
        opentermx_serial_t *handle, size_t *out_available) {
    if (!handle || !out_available) return OPENTERMX_ERR_INVALID;
    if (!handle->handle || handle->handle == INVALID_HANDLE_VALUE) return OPENTERMX_ERR_CLOSED;
    COMSTAT stat;
    DWORD errors = 0;
    if (!ClearCommError(handle->handle, &errors, &stat)) return OPENTERMX_ERR_IO;
    *out_available = (size_t)stat.cbInQue;
    return OPENTERMX_OK;
}

/* ===================================================================== */
/* Backend POSIX                                                          */
/* ===================================================================== */
#else /* !_WIN32 */

static int map_baud(int rate, speed_t *out) {
    switch (rate) {
        case 50:     *out = B50;     return 0;
        case 75:     *out = B75;     return 0;
        case 110:    *out = B110;    return 0;
        case 134:    *out = B134;    return 0;
        case 150:    *out = B150;    return 0;
        case 200:    *out = B200;    return 0;
        case 300:    *out = B300;    return 0;
        case 600:    *out = B600;    return 0;
        case 1200:   *out = B1200;   return 0;
        case 1800:   *out = B1800;   return 0;
        case 2400:   *out = B2400;   return 0;
        case 4800:   *out = B4800;   return 0;
        case 9600:   *out = B9600;   return 0;
        case 19200:  *out = B19200;  return 0;
        case 38400:  *out = B38400;  return 0;
        case 57600:  *out = B57600;  return 0;
        case 115200: *out = B115200; return 0;
        case 230400: *out = B230400; return 0;
#ifdef B460800
        case 460800: *out = B460800; return 0;
#endif
#ifdef B921600
        case 921600: *out = B921600; return 0;
#endif
        default: return -1;
    }
}

static int apply_config_posix(int fd, const opentermx_serial_config_t *cfg) {
    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) return OPENTERMX_ERR_CONFIG;

    cfmakeraw(&tio);

    speed_t speed;
    if (map_baud(cfg->baud_rate, &speed) != 0) return OPENTERMX_ERR_INVALID;
    cfsetispeed(&tio, speed);
    cfsetospeed(&tio, speed);

    tio.c_cflag &= ~CSIZE;
    switch (cfg->data_bits) {
        case 5: tio.c_cflag |= CS5; break;
        case 6: tio.c_cflag |= CS6; break;
        case 7: tio.c_cflag |= CS7; break;
        case 8: tio.c_cflag |= CS8; break;
        default: return OPENTERMX_ERR_INVALID;
    }

    switch (cfg->stop_bits) {
        case OPENTERMX_STOP_ONE:      tio.c_cflag &= ~CSTOPB; break;
        case OPENTERMX_STOP_ONE_HALF: /* no soportado en POSIX; usar 2 */
        case OPENTERMX_STOP_TWO:      tio.c_cflag |=  CSTOPB; break;
        default: return OPENTERMX_ERR_INVALID;
    }

    tio.c_cflag &= ~(PARENB | PARODD);
#ifdef CMSPAR
    tio.c_cflag &= ~CMSPAR;
#endif
    switch (cfg->parity) {
        case OPENTERMX_PARITY_NONE: break;
        case OPENTERMX_PARITY_EVEN: tio.c_cflag |= PARENB; break;
        case OPENTERMX_PARITY_ODD:  tio.c_cflag |= PARENB | PARODD; break;
#ifdef CMSPAR
        case OPENTERMX_PARITY_MARK:  tio.c_cflag |= PARENB | PARODD | CMSPAR; break;
        case OPENTERMX_PARITY_SPACE: tio.c_cflag |= PARENB | CMSPAR; break;
#else
        case OPENTERMX_PARITY_MARK:
        case OPENTERMX_PARITY_SPACE: return OPENTERMX_ERR_INVALID;
#endif
        default: return OPENTERMX_ERR_INVALID;
    }

#ifdef CRTSCTS
    tio.c_cflag &= ~CRTSCTS;
#endif
    tio.c_iflag &= ~(IXON | IXOFF | IXANY);
    switch (cfg->flow_control) {
        case OPENTERMX_FLOW_NONE: break;
#ifdef CRTSCTS
        case OPENTERMX_FLOW_RTSCTS: tio.c_cflag |= CRTSCTS; break;
#else
        case OPENTERMX_FLOW_RTSCTS: return OPENTERMX_ERR_INVALID;
#endif
        case OPENTERMX_FLOW_XONXOFF: tio.c_iflag |= IXON | IXOFF; break;
        default: return OPENTERMX_ERR_INVALID;
    }

    tio.c_cflag |= CLOCAL | CREAD;

    if (cfg->read_timeout_ms < 0) {
        tio.c_cc[VMIN]  = 1;
        tio.c_cc[VTIME] = 0;
    } else if (cfg->read_timeout_ms == 0) {
        tio.c_cc[VMIN]  = 0;
        tio.c_cc[VTIME] = 0;
    } else {
        tio.c_cc[VMIN]  = 0;
        tio.c_cc[VTIME] = (cc_t)((cfg->read_timeout_ms + 99) / 100); /* décimas */
    }

    if (tcsetattr(fd, TCSANOW, &tio) != 0) return OPENTERMX_ERR_CONFIG;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_open(
        const char *port_name,
        const opentermx_serial_config_t *config,
        opentermx_serial_t **out_handle) {

    if (!port_name || !config || !out_handle) return OPENTERMX_ERR_INVALID;
    *out_handle = NULL;

    int fd = open(port_name, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) return (errno == ENOENT) ? OPENTERMX_ERR_NOT_FOUND : OPENTERMX_ERR_OPEN;

    /* Quitamos O_NONBLOCK; el bloqueo lo controlan VMIN/VTIME. */
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags != -1) (void)fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);

    opentermx_serial_t *s = (opentermx_serial_t *)calloc(1, sizeof(*s));
    if (!s) {
        close(fd);
        return OPENTERMX_ERR_IO;
    }
    s->fd = fd;
    s->config = *config;

    int rc = apply_config_posix(fd, config);
    if (rc != OPENTERMX_OK) {
        close(fd);
        free(s);
        return rc;
    }
    tcflush(fd, TCIOFLUSH);
    *out_handle = s;
    return OPENTERMX_OK;
}

OPENTERMX_API void OPENTERMX_CALL opentermx_serial_close(opentermx_serial_t *handle) {
    if (!handle) return;
    if (handle->fd >= 0) close(handle->fd);
    free(handle);
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_configure(
        opentermx_serial_t *handle, const opentermx_serial_config_t *config) {
    if (!handle || !config) return OPENTERMX_ERR_INVALID;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    int rc = apply_config_posix(handle->fd, config);
    if (rc == OPENTERMX_OK) handle->config = *config;
    return rc;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_read(
        opentermx_serial_t *handle, uint8_t *buffer, size_t length, size_t *out_read) {
    if (!handle || !buffer || !out_read) return OPENTERMX_ERR_INVALID;
    *out_read = 0;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    if (length == 0) return OPENTERMX_OK;

    ssize_t n = read(handle->fd, buffer, length);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return OPENTERMX_ERR_TIMEOUT;
        return OPENTERMX_ERR_IO;
    }
    *out_read = (size_t)n;
    if (n == 0 && handle->config.read_timeout_ms > 0) return OPENTERMX_ERR_TIMEOUT;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_write(
        opentermx_serial_t *handle, const uint8_t *buffer, size_t length, size_t *out_written) {
    if (!handle || !buffer || !out_written) return OPENTERMX_ERR_INVALID;
    *out_written = 0;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    if (length == 0) return OPENTERMX_OK;

    size_t total = 0;
    while (total < length) {
        ssize_t n = write(handle->fd, buffer + total, length - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            *out_written = total;
            return OPENTERMX_ERR_IO;
        }
        total += (size_t)n;
    }
    *out_written = total;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_flush(opentermx_serial_t *handle) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    if (tcdrain(handle->fd) != 0) return OPENTERMX_ERR_IO;
    if (tcflush(handle->fd, TCIOFLUSH) != 0) return OPENTERMX_ERR_IO;
    return OPENTERMX_OK;
}

static int posix_set_bit(int fd, int bit, int on) {
    int status;
    if (ioctl(fd, TIOCMGET, &status) < 0) return OPENTERMX_ERR_IO;
    if (on) status |= bit; else status &= ~bit;
    if (ioctl(fd, TIOCMSET, &status) < 0) return OPENTERMX_ERR_IO;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_set_dtr(opentermx_serial_t *handle, int on) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    return posix_set_bit(handle->fd, TIOCM_DTR, on);
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_set_rts(opentermx_serial_t *handle, int on) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    return posix_set_bit(handle->fd, TIOCM_RTS, on);
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_send_break(
        opentermx_serial_t *handle, int32_t duration_ms) {
    if (!handle) return OPENTERMX_ERR_INVALID;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    /* tcsendbreak: el "duration" depende del sistema; aproximamos con 0 = ~0.25s */
    (void)duration_ms;
    if (tcsendbreak(handle->fd, 0) != 0) return OPENTERMX_ERR_IO;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_get_signals(
        opentermx_serial_t *handle, uint32_t *out_signals) {
    if (!handle || !out_signals) return OPENTERMX_ERR_INVALID;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    int status = 0;
    if (ioctl(handle->fd, TIOCMGET, &status) < 0) return OPENTERMX_ERR_IO;
    uint32_t s = 0;
    if (status & TIOCM_CTS) s |= OPENTERMX_SIGNAL_CTS;
    if (status & TIOCM_DSR) s |= OPENTERMX_SIGNAL_DSR;
    if (status & TIOCM_CAR) s |= OPENTERMX_SIGNAL_DCD;
    if (status & TIOCM_RI)  s |= OPENTERMX_SIGNAL_RI;
    if (status & TIOCM_DTR) s |= OPENTERMX_SIGNAL_DTR;
    if (status & TIOCM_RTS) s |= OPENTERMX_SIGNAL_RTS;
    *out_signals = s;
    return OPENTERMX_OK;
}

OPENTERMX_API int OPENTERMX_CALL opentermx_serial_available(
        opentermx_serial_t *handle, size_t *out_available) {
    if (!handle || !out_available) return OPENTERMX_ERR_INVALID;
    if (handle->fd < 0) return OPENTERMX_ERR_CLOSED;
    int avail = 0;
    if (ioctl(handle->fd, FIONREAD, &avail) < 0) return OPENTERMX_ERR_IO;
    *out_available = (size_t)(avail > 0 ? avail : 0);
    return OPENTERMX_OK;
}

#endif /* _WIN32 */