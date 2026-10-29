package plg.gesraco.tools.proxy.store.rest;

import plg.gesraco.tools.proxy.store.DocumentApi;
import plg.gesraco.tools.proxy.store.DocumentApiProvider;

/**
 * Provider of the built-in {@code alfresco-rest} document API.
 */
public final class AlfrescoRestApiProvider implements DocumentApiProvider {

    @Override
    public String name() {
        return AlfrescoRestApi.NAME;
    }

    @Override
    public DocumentApi create(Context context) {
        return new AlfrescoRestApi(context);
    }
}
