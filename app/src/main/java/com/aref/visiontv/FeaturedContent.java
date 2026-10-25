package com.aref.visiontv;

import android.graphics.Bitmap;

/** A single featured title with artwork, as delivered by the metadata provider. */
final class FeaturedContent {
    final String title;
    final String overview;
    final String metaLine;
    final String attribution;
    final String providerLabel;
    final String imageUrl;
    final Bitmap bitmap;
    final int ambient;

    FeaturedContent(String title, String overview, String metaLine, String attribution,
                    String providerLabel, String imageUrl, Bitmap bitmap, int ambient) {
        this.title = title;
        this.overview = overview;
        this.metaLine = metaLine;
        this.attribution = attribution;
        this.providerLabel = providerLabel;
        this.imageUrl = imageUrl;
        this.bitmap = bitmap;
        this.ambient = ambient;
    }

    FeaturedContent withBitmap(Bitmap value, int ambientColor) {
        return new FeaturedContent(title, overview, metaLine, attribution,
                providerLabel, imageUrl, value, ambientColor);
    }
}
