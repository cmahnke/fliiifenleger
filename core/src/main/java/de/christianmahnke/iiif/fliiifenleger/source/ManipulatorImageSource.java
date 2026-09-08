// SPDX-License-Identifier: MIT
// Copyright (c) 2025 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.source;

public interface ManipulatorImageSource extends  ImageSource {

    void load(ImageSource baseSource);

}

