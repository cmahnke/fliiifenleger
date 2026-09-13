// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.iiif.fliiifenleger.ultrahdr;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.sink.ServiceExtension;

/**
 * {@link ServiceExtension} for UltraHDR gain-map tiles
 * ({@link UltraHdrTileSink#HDR_PROFILE_URI}).
 */
@AutoService(ServiceExtension.class)
public class HdrServiceExtension implements ServiceExtension {

    @Override
    public String profileUri() {
        return UltraHdrTileSink.HDR_PROFILE_URI;
    }

    @Override
    public String contextUri() {
        return UltraHdrTileSink.HDR_CONTEXT_URI;
    }

    @Override
    public String schemaResource() {
        return "/schema/hdr-service.json";
    }
}
