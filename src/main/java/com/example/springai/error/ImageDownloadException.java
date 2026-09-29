package com.example.springai.error;

/**
 * Raised when the vision endpoint cannot obtain the image it was asked to analyse.
 */
public class ImageDownloadException extends RuntimeException {

    private final String imageUrl;

    public ImageDownloadException(String imageUrl, String reason, Throwable cause) {
        super("Could not download the image at " + imageUrl + ": " + reason, cause);
        this.imageUrl = imageUrl;
    }

    public String getImageUrl() {
        return imageUrl;
    }
}
