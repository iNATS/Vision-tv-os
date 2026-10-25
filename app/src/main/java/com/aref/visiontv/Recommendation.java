package com.aref.visiontv;

/** A discoverable title offered as a recommendation for the focused app. */
final class Recommendation {
    final String title;
    final String overview;
    final String metaLine;
    final String posterUrl;
    final String backdropUrl;
    final String providerLabel;

    Recommendation(String title, String overview, String metaLine,
                   String posterUrl, String backdropUrl, String providerLabel) {
        this.title = title;
        this.overview = overview;
        this.metaLine = metaLine;
        this.posterUrl = posterUrl;
        this.backdropUrl = backdropUrl;
        this.providerLabel = providerLabel;
    }
}
