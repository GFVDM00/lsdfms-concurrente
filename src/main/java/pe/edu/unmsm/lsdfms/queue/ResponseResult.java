package pe.edu.unmsm.lsdfms.queue;

/**
 * Representa el resultado de la ejecucion de una peticion
 * despachada desde la cola sincronizada.
 */
public class ResponseResult {
    private final boolean success;
    private final String message;
    private final long affectedOffset;
    private final Object data;

    public ResponseResult(boolean success, String message, long affectedOffset, Object data) {
        this.success = success;
        this.message = message;
        this.affectedOffset = affectedOffset;
        this.data = data;
    }

    public static ResponseResult ok(String message, long affectedOffset, Object data) {
        return new ResponseResult(true, message, affectedOffset, data);
    }

    public static ResponseResult error(String message) {
        return new ResponseResult(false, message, -1, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public long getAffectedOffset() {
        return affectedOffset;
    }

    public Object getData() {
        return data;
    }

    @Override
    public String toString() {
        return "ResponseResult{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", affectedOffset=" + affectedOffset +
                ", data=" + data +
                '}';
    }
}
