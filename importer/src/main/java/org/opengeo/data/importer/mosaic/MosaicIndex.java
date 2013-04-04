package org.opengeo.data.importer.mosaic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import org.apache.commons.io.FilenameUtils;
import org.geotools.data.DataUtilities;
import org.geotools.data.FeatureWriter;
import org.geotools.data.Transaction;
import org.geotools.data.directory.DirectoryDataStore;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.shapefile.ShpFileType;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.jts.JTS;
import org.geotools.util.logging.Logging;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterators;
import com.google.common.io.Files;
import com.vividsolutions.jts.geom.Polygon;

/**
 * Helper to write out mosaic index shapefile and properties files. 
 * 
 * @author Justin Deoliveira, OpenGeo
 */
public class MosaicIndex {

    static Logger LOGGER = Logging.getLogger(MosaicIndex.class);

    Mosaic mosaic;

    public MosaicIndex(Mosaic mosaic) {
        this.mosaic = mosaic;
    }

    public File getFile() {
        return new File(mosaic.getFile(), mosaic.getName() + ".shp");
    }

    public void delete() throws IOException {
        for (File f : mosaic.getFile().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                if ("sample_image".equalsIgnoreCase(name)) {
                    return true;
                }

                if (!mosaic.getName().equalsIgnoreCase(FilenameUtils.getBaseName(name))) {
                    return false;
                }

                String ext = FilenameUtils.getExtension(name);
                return "properties".equalsIgnoreCase(ext) 
                    || ShpFileType.valueOf(ext.toUpperCase()) != null;
            }
        })) {
            f.delete();
        }
    }

    public void write() throws IOException {
        //delete if already exists
        delete();

        Collection<Granule> granules = mosaic.granules();
        if (granules.isEmpty()) {
            LOGGER.warning("No granules in mosaic, nothing to write");
            return;
        }

        Granule first = Iterators.find(granules.iterator(), new Predicate<Granule>() {
            @Override
            public boolean apply(Granule input) {
                return input.getEnvelope() != null && 
                    input.getEnvelope().getCoordinateReferenceSystem() != null;
            }
        });
        if (first == null) {
            throw new IOException("Unable to determine CRS for mosaic");
        }

        Envelope2D envelope = new Envelope2D(first.getEnvelope());

        //create index schema
        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName(mosaic.getName());
        typeBuilder.setCRS(envelope.getCoordinateReferenceSystem());
        typeBuilder.add("the_geom", Polygon.class);
        typeBuilder.add("location", String.class);

        if (mosaic.getTimeMode() != TimeMode.NONE) {
            typeBuilder.add("time", Date.class);
        }

        //create a new shapefile feature store
        ShapefileDataStoreFactory shpFactory = new ShapefileDataStoreFactory();
        DirectoryDataStore dir = new DirectoryDataStore(mosaic.getFile(), 
            new ShapefileDataStoreFactory.ShpFileStoreFactory(shpFactory, new HashMap()));

        try {
           dir.createSchema(typeBuilder.buildFeatureType());

           FeatureWriter<SimpleFeatureType, SimpleFeature> w = 
                   dir.getFeatureWriterAppend(mosaic.getName(), Transaction.AUTO_COMMIT);

           try {
               for (Granule g : mosaic.granules()) {
                   if (g.getEnvelope() == null) {
                       LOGGER.warning("Skipping " + g.getFile().getAbsolutePath() + ", no envelope");
                   }
    
                   SimpleFeature f = w.next();
                   f.setDefaultGeometry(JTS.toGeometry((BoundingBox)g.getEnvelope()));
                   f.setAttribute("location", g.getFile().getName());
                   if (mosaic.getTimeMode() != TimeMode.NONE) {
                       f.setAttribute("time", g.getTimestamp());
                   }
                   w.write();

                   //track total bounds
                   envelope.include(g.getEnvelope());
               }

           }
           finally {
               w.close();
           }
        }
        finally {
            dir.dispose();
        }

        double width = first.getGrid().getGridRange2D().getWidth();
        double height = first.getGrid().getGridRange2D().getHeight();

        //write out the properties file
        Properties props = new Properties();
        props.setProperty("Name", mosaic.getName());
        props.setProperty("Levels", String.format("%f,%f", first.getEnvelope().getWidth()/width, 
            first.getEnvelope().getHeight()/height));
        props.setProperty("LevelsNum", "1");
        props.setProperty("LocationAttribute", "location");

        if (mosaic.getTimeMode() != TimeMode.NONE) {
            props.setProperty("TimeAttribute", "time");
        }

        FileOutputStream fout = new FileOutputStream(
            new File(mosaic.getFile(), mosaic.getName()+".properties"));
        try {
            props.store(fout, null);
            fout.flush();
        }
        finally {
            fout.close();
        }
    }
}
