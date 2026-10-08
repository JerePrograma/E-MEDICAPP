package ar.emedicapp.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiError> application(AppException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<ApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_REQUEST", "Revisá los datos enviados."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> oversized(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413)
                .body(
                        new ApiError(
                                "DOCUMENT_TOO_LARGE",
                                "El archivo supera el tamaño máximo admitido."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException exception) {
        return ResponseEntity.status(409)
                .body(
                        new ApiError(
                                "DATA_CONFLICT",
                                "La operación entra en conflicto con datos vigentes. Actualizá e intentá nuevamente."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException exception) {
        return ResponseEntity.status(404)
                .body(new ApiError("NOT_FOUND", "No se encontró el recurso solicitado."));
    }
}
