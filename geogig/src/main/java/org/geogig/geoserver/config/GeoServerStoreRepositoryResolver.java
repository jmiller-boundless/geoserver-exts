package org.geogig.geoserver.config;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import org.apache.commons.io.FilenameUtils;
import org.locationtech.geogig.geotools.data.GeoGigDataStoreFactory;

import com.google.common.base.Throwables;

public class GeoServerStoreRepositoryResolver implements GeoGigDataStoreFactory.RepositoryLookup {

    @Override
    public URI resolve(final String repository) {
        RepositoryManager repositoryManager = RepositoryManager.get();
        try {
            RepositoryInfo info = repositoryManager.get(repository);
            File locFile = new File(info.getLocation());
            return locFile.toURI();
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }


}
