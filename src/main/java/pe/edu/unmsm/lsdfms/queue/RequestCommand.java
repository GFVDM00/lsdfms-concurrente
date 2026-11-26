package pe.edu.unmsm.lsdfms.queue;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Encapsula una peticion de alteracion que viaja a traves de la cola sincronizada.
 * Permite la coordinacion asincrona entre el hilo HTTP productor y el hilo consumidor.
 */
public class RequestCommand<T> {
    private final String requestId;
    private final RequestType type;
    private final T payload;
    private final long targetOffset;
    private final long timestamp;
    private final CompletableFuture<ResponseResult> futureResponse;

    public RequestCommand(RequestType type, T payload, long targetOffset) {
        this.requestId = UUID.randomUUID().toString();
        this.type = type;
        this.payload = payload;
        this.targetOffset = targetOffset;
        this.timestamp = System.currentTimeMillis();
        this.futureResponse = new CompletableFuture<>();
    }

    public static <T> RequestCommand<T> ofInsert(T payload) {
        return new RequestCommand<>(RequestType.INSERT, payload, -1L);
    }

    public static RequestCommand<Void> ofDelete(long offset) {
        return new RequestCommand<>(RequestType.DELETE_LOGICAL, null, offset);
    }

    public static <T> RequestCommand<T> ofUpdate(long offset, T payload) {
        return new RequestCommand<>(RequestType.UPDATE, payload, offset);
    }

    public String getRequestId() {
        return requestId;
    }

    public RequestType getType() {
        return type;
    }

    public T getPayload() {
        return payload;
    }

    public long getTargetOffset() {
        return targetOffset;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public CompletableFuture<ResponseResult> getFutureResponse() {
        return futureResponse;
    }

    public void complete(ResponseResult result) {
        this.futureResponse.complete(result);
    }

    public void completeExceptionally(Throwable ex) {
        this.futureResponse.completeExceptionally(ex);
    }
}
