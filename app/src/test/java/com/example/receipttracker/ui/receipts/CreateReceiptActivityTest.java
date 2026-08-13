package com.example.receipttracker.ui.receipts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Constants on CreateReceiptActivity are mirrored in ScanReceiptActivity
 * (private, with the same string values). These tests pin the public
 * constants so that adding a new mode does not silently drift them.
 */
class CreateReceiptActivityTest {

    @Test
    @DisplayName("EXTRA_START_MODE is the canonical key string")
    void shouldExposeStartModeExtra() {
        assertThat(CreateReceiptActivity.EXTRA_START_MODE).isEqualTo("start_mode");
    }


    @Test
    @DisplayName("MODE_CAMERA is the literal 'camera'")
    void shouldExposeCameraMode() {
        assertThat(CreateReceiptActivity.MODE_CAMERA).isEqualTo("camera");
    }


    @Test
    @DisplayName("MODE_GALLERY is the literal 'gallery'")
    void shouldExposeGalleryMode() {
        assertThat(CreateReceiptActivity.MODE_GALLERY).isEqualTo("gallery");
    }


    @Test
    @DisplayName("MODE_CAMERA and MODE_GALLERY are distinct")
    void shouldKeepCameraAndGalleryDistinct() {
        assertThat(CreateReceiptActivity.MODE_CAMERA)
                .isNotEqualTo(CreateReceiptActivity.MODE_GALLERY);
    }
}
