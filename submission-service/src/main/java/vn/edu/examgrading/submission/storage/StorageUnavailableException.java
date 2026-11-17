package vn.edu.examgrading.submission.storage;

/** Wraps MinIO/S3 client checked exceptions behind the gateway boundary. */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
