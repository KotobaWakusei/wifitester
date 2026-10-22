package me.paranoid.wifikill;

/**
 * The Material colour roles used by the hand built views.
 *
 * <p>The application is compiled with a non transitive resource class, so the
 * attributes of the Material library are addressed through the library's own
 * {@code R} class. The application specific roles stay in {@link R#attr}.</p>
 */
final class Attrs {

    private Attrs() {
    }

    // ---- Material 3 colour roles -----------------------------------------

    static final int COLOR_PRIMARY = com.google.android.material.R.attr.colorPrimary;
    static final int COLOR_ON_PRIMARY = com.google.android.material.R.attr.colorOnPrimary;
    static final int COLOR_PRIMARY_CONTAINER =
            com.google.android.material.R.attr.colorPrimaryContainer;
    static final int COLOR_ON_PRIMARY_CONTAINER =
            com.google.android.material.R.attr.colorOnPrimaryContainer;
    static final int COLOR_SECONDARY_CONTAINER =
            com.google.android.material.R.attr.colorSecondaryContainer;
    static final int COLOR_ON_SECONDARY_CONTAINER =
            com.google.android.material.R.attr.colorOnSecondaryContainer;
    static final int COLOR_ERROR = com.google.android.material.R.attr.colorError;
    static final int COLOR_ERROR_CONTAINER =
            com.google.android.material.R.attr.colorErrorContainer;
    static final int COLOR_ON_ERROR_CONTAINER =
            com.google.android.material.R.attr.colorOnErrorContainer;
    static final int COLOR_ON_SURFACE = com.google.android.material.R.attr.colorOnSurface;
    static final int COLOR_ON_SURFACE_VARIANT =
            com.google.android.material.R.attr.colorOnSurfaceVariant;
    static final int COLOR_OUTLINE_VARIANT =
            com.google.android.material.R.attr.colorOutlineVariant;
    static final int COLOR_SURFACE = com.google.android.material.R.attr.colorSurface;
    static final int COLOR_SURFACE_CONTAINER =
            com.google.android.material.R.attr.colorSurfaceContainer;
    static final int COLOR_SURFACE_CONTAINER_HIGH =
            com.google.android.material.R.attr.colorSurfaceContainerHigh;
    static final int COLOR_SURFACE_CONTAINER_LOW =
            com.google.android.material.R.attr.colorSurfaceContainerLow;
    static final int COLOR_SURFACE_CONTAINER_LOWEST =
            com.google.android.material.R.attr.colorSurfaceContainerLowest;
}
