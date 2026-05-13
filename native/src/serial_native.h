#ifndef OPENTERMX_SERIAL_NATIVE_H
#define OPENTERMX_SERIAL_NATIVE_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#  if defined(OPENTERMX_BUILDING_DLL)
#    define OPENTERMX_API __declspec(dllexport)
#  else
#    define OPENTERMX_API __declspec(dllimport)
#  endif
#  define OPENTERMX_CALL __cdecl
#else
#  if defined(OPENTERMX_BUILDING_DLL)
#    define OPENTERMX_API __attribute__((visibility("default")))
#  else
#    define OPENTERMX_API
#  endif
#  define OPENTERMX_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ---------- Códigos de retorno ---------------------------------------- */
#define OPENTERMX_OK              0
#define OPENTERMX_ERR_INVALID    -1
#define OPENTERMX_ERR_OPEN       -2
#define OPENTERMX_ERR_CONFIG     -3
#define OPENTERMX_ERR_IO         -4
#define OPENTERMX_ERR_TIMEOUT    -5
#define OPENTERMX_ERR_CLOSED     -6
#define OPENTERMX_ERR_NOT_FOUND  -7

/* ---------- Enumeraciones (valores estables para JNA) ----------------- */
typedef enum {
    OPENTERMX_PARITY_NONE  = 0,
    OPENTERMX_PARITY_ODD   = 1,
    OPENTERMX_PARITY_EVEN  = 2,
    OPENTERMX_PARITY_MARK  = 3,
    OPENTERMX_PARITY_SPACE = 4
} opentermx_parity_t;

typedef enum {
    OPENTERMX_STOP_ONE      = 0,
    OPENTERMX_STOP_ONE_HALF = 1,
    OPENTERMX_STOP_TWO      = 2
} opentermx_stop_bits_t;

typedef enum {
    OPENTERMX_FLOW_NONE     = 0,
    OPENTERMX_FLOW_RTSCTS   = 1,
    OPENTERMX_FLOW_XONXOFF  = 2
} opentermx_flow_t;

/* Bits en la máscara devuelta por opentermx_serial_get_signals */
#define OPENTERMX_SIGNAL_CTS  (1u << 0)
#define OPENTERMX_SIGNAL_DSR  (1u << 1)
#define OPENTERMX_SIGNAL_DCD  (1u << 2)
#define OPENTERMX_SIGNAL_RI   (1u << 3)
#define OPENTERMX_SIGNAL_DTR  (1u << 4)
#define OPENTERMX_SIGNAL_RTS  (1u << 5)

/* ---------- Tipo opaco ------------------------------------------------ */
typedef struct opentermx_serial opentermx_serial_t;

/* ---------- Configuración del puerto ---------------------------------- */
typedef struct {
    int32_t              baud_rate;
    int32_t              data_bits;     /* 5..8                                 */
    opentermx_stop_bits_t stop_bits;
    opentermx_parity_t   parity;
    opentermx_flow_t     flow_control;
    int32_t              read_timeout_ms;  /* 0 = no bloqueante; <0 = bloqueante */
    int32_t              write_timeout_ms;
} opentermx_serial_config_t;

/* ---------- API ------------------------------------------------------- */

/** Versión de la librería como entero empaquetado: (mayor<<16)|(menor<<8)|patch. */
OPENTERMX_API uint32_t OPENTERMX_CALL opentermx_version(void);

/** Mensaje de error legible para un código devuelto por la API. */
OPENTERMX_API const char *OPENTERMX_CALL opentermx_strerror(int code);

/**
 * Abre un puerto serial.
 * @param port_name nombre del puerto ("COM3", "/dev/ttyUSB0", ...).
 * @param config configuración inicial (no puede ser NULL).
 * @param out_handle salida: handle opaco; debe liberarse con opentermx_serial_close.
 * @return OPENTERMX_OK o un código de error.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_open(
        const char *port_name,
        const opentermx_serial_config_t *config,
        opentermx_serial_t **out_handle);

/** Cierra el puerto y libera el handle. Seguro pasar NULL. */
OPENTERMX_API void OPENTERMX_CALL opentermx_serial_close(opentermx_serial_t *handle);

/**
 * Reconfigura un puerto ya abierto.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_configure(
        opentermx_serial_t *handle,
        const opentermx_serial_config_t *config);

/**
 * Lee hasta @p length bytes en @p buffer.
 * @param out_read número de bytes efectivamente leídos (puede ser 0 en timeout).
 * @return OPENTERMX_OK, OPENTERMX_ERR_TIMEOUT o un código de error.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_read(
        opentermx_serial_t *handle,
        uint8_t *buffer,
        size_t length,
        size_t *out_read);

/**
 * Escribe @p length bytes desde @p buffer.
 * @param out_written número de bytes efectivamente escritos.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_write(
        opentermx_serial_t *handle,
        const uint8_t *buffer,
        size_t length,
        size_t *out_written);

/** Vacía los buffers de E/S y espera a que terminen las escrituras pendientes. */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_flush(opentermx_serial_t *handle);

/** Activa o desactiva DTR. */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_set_dtr(opentermx_serial_t *handle, int on);
/** Activa o desactiva RTS. */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_set_rts(opentermx_serial_t *handle, int on);

/**
 * Envía una condición BREAK de @p duration_ms milisegundos.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_send_break(
        opentermx_serial_t *handle,
        int32_t duration_ms);

/**
 * Lee el estado de las líneas de control.
 * @param out_signals máscara compuesta con OPENTERMX_SIGNAL_*.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_get_signals(
        opentermx_serial_t *handle,
        uint32_t *out_signals);

/**
 * Devuelve cuántos bytes hay disponibles para leer sin bloquear.
 * @param out_available salida: bytes disponibles.
 */
OPENTERMX_API int OPENTERMX_CALL opentermx_serial_available(
        opentermx_serial_t *handle,
        size_t *out_available);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* OPENTERMX_SERIAL_NATIVE_H */