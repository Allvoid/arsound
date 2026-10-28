package app.revanced.extension.soundcloud.lyrics.transcribe;

/** A recognition that stopped for a {@link Failure}. Thrown by {@link TranscriptionJob}, caught by {@link TranscriptionManager}. */
final class JobFailed extends Exception {
    final Failure failure;

    JobFailed(Failure failure, Throwable cause) {
        super(failure.name(), cause);
        this.failure = failure;
    }
}
