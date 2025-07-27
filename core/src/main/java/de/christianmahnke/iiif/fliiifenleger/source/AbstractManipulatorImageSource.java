/**
 * Fliiifenleger
 * Copyright (C) 2025  Christian Mahnke
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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