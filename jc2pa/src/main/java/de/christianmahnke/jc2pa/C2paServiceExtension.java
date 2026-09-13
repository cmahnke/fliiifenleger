// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Christian Mahnke
package de.christianmahnke.jc2pa;

import com.google.auto.service.AutoService;
import de.christianmahnke.iiif.fliiifenleger.sink.ServiceExtension;

/**
 * {@link ServiceExtension} for C2PA Content Credentials
 * ({@link C2paTileSink#C2PA_PROFILE_URI}).
 */
@AutoService(ServiceExtension.class)
public class C2paServiceExtension implements ServiceExtension {

    @Override
    public String profileUri() {
        return C2paTileSink.C2PA_PROFILE_URI;
    }

    @Override
    public String contextUri() {
        return C2paTileSink.C2PA_CONTEXT_URI;
    }

    @Override
    public String schemaResource() {
        return "/schema/c2pa-service.json";
    }
}
