package org.geogit.layer;

import static org.geoserver.catalog.Predicates.and;
import static org.geoserver.catalog.Predicates.equal;

import java.util.List;
import java.util.logging.Logger;

import org.geogit.geotools.data.GeoGitDataStoreFactory;
import org.geoserver.catalog.AuthorityURLInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogException;
import org.geoserver.catalog.CatalogInfo;
import org.geoserver.catalog.DataStoreInfo;
import org.geoserver.catalog.LayerIdentifierInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.catalog.event.CatalogAddEvent;
import org.geoserver.catalog.event.CatalogListener;
import org.geoserver.catalog.event.CatalogModifyEvent;
import org.geoserver.catalog.event.CatalogPostModifyEvent;
import org.geoserver.catalog.event.CatalogRemoveEvent;
import org.geoserver.catalog.impl.AuthorityURL;
import org.geoserver.catalog.impl.LayerIdentifier;
import org.geoserver.catalog.util.CloseableIterator;
import org.geoserver.config.GeoServer;
import org.geoserver.wms.WMSInfo;
import org.geotools.util.logging.Logging;
import org.opengis.filter.Filter;

/**
 * Handles the following events:
 * <ul>
 * <li> {@link WorkspaceInfo} renamed: all geogit layers of stores in that workspace get their
 * authority URL identifiers updated
 * <li> {@link DataStoreInfo} renamed: all geogit layers of stores in that workspace get their
 * authority URL identifiers updated
 * <li> {@link DataStoreInfo} workspace changed: all geogit layers of stores in that workspace get
 * their authority URL identifiers updated
 * <li> {@link LayerInfo} added: if its a geogit layer, creates its geogit authority URL identifier
 * and saves the layer info
 * </ul>
 */
public class GeogitLayerIntegrationListener implements CatalogListener {

    private static final Logger LOGGER = Logging.getLogger(GeogitLayerIntegrationListener.class);

    public static final String AUTHORITY_URL_NAME = "GEOGIT_ENTRY_POINT";

    public static final String AUTHORITY_URL = "http://geogit.org";

    private GeoServer geoserver;

    /**
     */
    public GeogitLayerIntegrationListener(GeoServer geoserver) {
        LOGGER.fine("Initialized " + getClass().getName());
        this.geoserver = geoserver;
        this.geoserver.getCatalog().addListener(this);
    }

    @Override
    public void handleAddEvent(CatalogAddEvent event) throws CatalogException {
        if (!(event.getSource() instanceof LayerInfo)) {
            return;
        }
        LayerInfo layer = (LayerInfo) event.getSource();
        if (!isGeogitLayer(layer)) {
            return;
        }
        if (!forceServiceRootLayerHaveGeogitAuthURL()) {
            return;
        }

        setIdentifier(layer);
    }

    private static final ThreadLocal<CatalogInfo> PRE_MOFIFY_EVENT = new ThreadLocal<CatalogInfo>();

    @Override
    public void handleModifyEvent(CatalogModifyEvent event) throws CatalogException {
        if (PRE_MOFIFY_EVENT.get() != null) {
            LOGGER.fine("pre-modify event exists, ignoring handleModifyEvent ("
                    + PRE_MOFIFY_EVENT.get() + ")");
            return;
        }

        final CatalogInfo source = event.getSource();

        boolean tryPostUpdate = (source instanceof WorkspaceInfo)
                || ((source instanceof DataStoreInfo) && isGeogitStore((DataStoreInfo) source));

        final List<String> propertyNames = event.getPropertyNames();
        tryPostUpdate &= propertyNames.contains("name");

        if (tryPostUpdate) {
            LOGGER.fine("Storing event for post-handling on " + source);
            PRE_MOFIFY_EVENT.set(source);
        }
    }

    @Override
    public void handlePostModifyEvent(CatalogPostModifyEvent event) throws CatalogException {
        final CatalogInfo preModifySource = PRE_MOFIFY_EVENT.get();
        if (preModifySource == null) {
            return;
        }
        if (!event.getSource().getId().equals(preModifySource.getId())) {
            return;
        }
        PRE_MOFIFY_EVENT.remove();
        LOGGER.fine("handing post-modify event for " + preModifySource);

        CatalogInfo source = event.getSource();

        if (source instanceof WorkspaceInfo) {
            handlePostWorkspaceChange((WorkspaceInfo) source);
        } else if (source instanceof DataStoreInfo) {
            handlePostGeogitStoreChange((DataStoreInfo) source);
        }
    }

    private void handlePostGeogitStoreChange(DataStoreInfo source) {
        Catalog catalog = geoserver.getCatalog();

        final String storeId = source.getId();
        Filter filter = equal("resource.store.id", storeId);

        CloseableIterator<LayerInfo> affectedLayers = catalog.list(LayerInfo.class, filter);
        updateLayers(affectedLayers);
    }

    private void handlePostWorkspaceChange(WorkspaceInfo source) {
        Catalog catalog = geoserver.getCatalog();
        final String wsId = source.getId();
        final String storeType = GeoGitDataStoreFactory.DISPLAY_NAME;

        Filter filter = and(equal("resource.store.workspace.id", wsId),
                equal("resource.store.type", storeType));

        CloseableIterator<LayerInfo> affectedLayers = catalog.list(LayerInfo.class, filter);
        updateLayers(affectedLayers);
    }

    private void updateLayers(CloseableIterator<LayerInfo> affectedLayers) {
        try {
            while (affectedLayers.hasNext()) {
                LayerInfo geogitLayer = affectedLayers.next();
                setIdentifier(geogitLayer);
            }
        } finally {
            affectedLayers.close();
        }
    }

    private boolean forceServiceRootLayerHaveGeogitAuthURL() {
        LOGGER.fine("Checking for root layer geogit auth URL");

        WMSInfo serviceInfo = geoserver.getService(WMSInfo.class);
        if (serviceInfo == null) {
            LOGGER.info("No WMSInfo available in GeoServer. This is strange but can happen");
            return false;
        }

        GeoServer geoserver = this.geoserver;
        List<AuthorityURLInfo> authorityURLs = serviceInfo.getAuthorityURLs();
        for (AuthorityURLInfo urlInfo : authorityURLs) {
            if (AUTHORITY_URL_NAME.equals(urlInfo.getName())) {
                LOGGER.fine("geogit root layer auth URL already exists");
                return true;
            }
        }

        AuthorityURL geogitAuthURL = new AuthorityURL();
        geogitAuthURL.setName(AUTHORITY_URL_NAME);
        geogitAuthURL.setHref(AUTHORITY_URL);
        serviceInfo.getAuthorityURLs().add(geogitAuthURL);

        LOGGER.fine("Saving geogit root layer auth URL");
        geoserver.save(serviceInfo);
        LOGGER.info("geogit root layer auth URL saved");
        return true;
    }

    /**
     * Does nothing
     */
    @Override
    public void handleRemoveEvent(CatalogRemoveEvent event) throws CatalogException {
        // do nothing
    }

    /**
     * Does nothing
     */
    @Override
    public void reloaded() {
        // do nothing
    }

    private void setIdentifier(LayerInfo layer) {
        LOGGER.fine("Updating geogit auth identifier for layer " + layer.prefixedName());
        final String layerIdentifier = buildLayerIdentifier(layer);
        updateIdentifier(layer, layerIdentifier);
    }

    private void updateIdentifier(LayerInfo geogitLayer, final String newIdentifier) {

        List<LayerIdentifierInfo> layerIdentifiers = geogitLayer.getIdentifiers();
        {
            LayerIdentifierInfo id = null;
            for (LayerIdentifierInfo identifier : layerIdentifiers) {
                if (AUTHORITY_URL_NAME.equals(identifier.getAuthority())) {
                    id = identifier;
                    break;
                }
            }
            if (id != null) {
                if (newIdentifier.equals(id.getIdentifier())) {
                    return;
                }
                layerIdentifiers.remove(id);
            }
        }

        LayerIdentifier newId = new LayerIdentifier();
        newId.setAuthority(AUTHORITY_URL_NAME);
        newId.setIdentifier(newIdentifier);
        layerIdentifiers.add(newId);
        Catalog catalog = geoserver.getCatalog();
        catalog.save(geogitLayer);
        LOGGER.info("Updated geogit auth identifier for layer " + geogitLayer.prefixedName()
                + " as " + newIdentifier);
    }

    private String buildLayerIdentifier(LayerInfo geogitLayer) {

        ResourceInfo resource = geogitLayer.getResource();
        StoreInfo store = resource.getStore();
        WorkspaceInfo workspace = store.getWorkspace();

        String identifier = workspace.getName() + ":" + store.getName();

        return identifier;
    }

    private boolean isGeogitLayer(LayerInfo layer) {
        ResourceInfo resource = layer.getResource();
        StoreInfo store = resource.getStore();
        return isGeogitStore(store);
    }

    private boolean isGeogitStore(StoreInfo store) {
        final String storeType = store.getType();
        boolean isGeogitLayer = GeoGitDataStoreFactory.DISPLAY_NAME.equals(storeType);
        return isGeogitLayer;
    }
}
