// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

import java.net.URL;
import java.util.Map;

/**
 * An abstract base class for ManipulatorImageSource implementations to reduce boilerplate.
 * It delegates common methods to the wrapped base source.
 */
public abstract class AbstractManipulatorImageSource extends AbstractImageSource implements ManipulatorImageSource {

    /**
     * The underlying {@link ImageSource} that this manipulator wraps.
     */
    protected ImageSource baseSource;

    /**
     * {@inheritDoc}
     * <p>
     * Stores the provided base source for future manipulation and delegation.
     */
    @Override
    public void load(ImageSource baseSource) {
        this.baseSource = baseSource;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This operation is not supported for manipulators, as they are designed to wrap an
     * existing {@link ImageSource} rather than loading a resource from a URL directly.
     *
     * @throws UnsupportedOperationException always.
     */
    @Override
    public void load(URL url) {
        // This method is not applicable to manipulators, which load a base source instead.
        throw new UnsupportedOperationException("load(URL) is not supported on a ManipulatorImageSource. Use load(ImageSource) instead.");
    }

    /**
     * {@inheritDoc}
     * <p>Delegates to the base source.
     */
    @Override public URL getUrl() { return baseSource.getUrl(); }
    /**
     * {@inheritDoc}
     * <p>Delegates to the base source.
     */
    @Override public int getWidth() { return baseSource.getWidth(); }
    /**
     * {@inheritDoc}
     * <p>Delegates to the base source.
     */
    @Override public int getHeight() { return baseSource.getHeight(); }
    /**
     * {@inheritDoc}
     * <p>Delegates to the base source.
     */
    @Override public Map<String, Object> getMetadata() { return baseSource.getMetadata(); }
}