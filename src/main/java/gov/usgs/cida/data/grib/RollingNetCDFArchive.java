package gov.usgs.cida.data.grib;

import com.google.common.base.Joiner;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import gov.usgs.cida.gdp.coreprocessing.analysis.grid.CRSUtility;
import gov.usgs.cida.gdp.coreprocessing.analysis.grid.GridUtility;
import java.io.Closeable;
import java.io.File;
import java.io.Flushable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.*;
import ucar.nc2.*;
import ucar.nc2.constants.AxisType;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.nc2.dataset.CoordinateAxis1DTime;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.dt.GridCoordSystem;
import ucar.nc2.dt.GridDatatype;
import ucar.nc2.dt.grid.GridDataset;
import ucar.nc2.ft.FeatureDataset;
import ucar.nc2.ft.FeatureDatasetFactoryManager;
import ucar.nc2.time.CalendarDate;
import ucar.unidata.geoloc.ProjectionImpl;

/**
 *
 * @author Jordan Walker <jiwalker@usgs.gov>
 */
public class RollingNetCDFArchive implements Closeable, Flushable {

    public static final String DIM = "dimension";
    public static final String VAR = "variable";
    public static final String XY = "xy";
    
    private static final Logger log = LoggerFactory.getLogger(
            RollingNetCDFArchive.class);
    
    private NetcdfFileWriteable netcdf;
    private GridDataset gridDs;
    private CoordinateReferenceSystem crs;
    private GridDatatype gdt;
    private Map<String, List<String>> excludes;
    private String unlimited;
    private String gridMapping;
    private List<String> gridVariables;

    // should be able to open existing file here
    public RollingNetCDFArchive(File rollingFile) throws IOException {
        String fileAsString = rollingFile.getAbsolutePath();
        if (rollingFile.exists() && NetcdfFileWriteable.canOpen(fileAsString)) {
            netcdf = NetcdfFileWriteable.openExisting(fileAsString);
        }
        else {
            netcdf = NetcdfFileWriteable.createNew(fileAsString);
            gridDs = null;
            crs = null;
            gdt = null;
        }
        excludes = Maps.newHashMap();
        gridVariables = null;
        unlimited = "time";
        gridMapping = "Latitude_Longitude";
    }
    
    public void setExcludeList(String key, String... excludes) {
        this.excludes.put(key, Lists.newArrayList(excludes));
    }
    
    public void setUnlimitedDimension(String dimName) {
        this.unlimited = dimName;
    }
    
    public void setGridMapping(String gridMappingName) {
        this.gridMapping = gridMappingName;
    }
    
    public void setGridVariables(String... varName) {
        gridVariables = Lists.newArrayList(varName);
    }
    
    public void define(File gribPrototype) throws IOException, FactoryException, TransformException, InvalidRangeException {
        FeatureDataset featureDataset = getFeatureDatasetFromFile(gribPrototype);
        gridDs = getGridDatasetFromFeatureDataset(featureDataset);
        gdt = getDatatypeFromDataset(gridDs);
        crs = getCRSFromDatatype(gdt);
        
        NetcdfDataset srcNc = gridDs.getNetcdfDataset();
        
        Dimension unlimitedDim = null;
        List<String> dimExcludes = excludes.get(DIM);
        for (Dimension dim : srcNc.getDimensions()) {
            if (unlimited.equals(dim.getName())) {
                unlimitedDim = dim;
            }
            else if (dimExcludes != null && dimExcludes.contains(dim.getName())) {
                // hold this one out
            }
            else {
                netcdf.addDimension(dim.getName(), dim.getLength());
            }
        }
        if (unlimitedDim != null) {
            netcdf.addUnlimitedDimension(unlimited);
        }
        
        Variable latVar = null;
        Variable lonVar = null;
        if (excludes.containsKey(XY)) {
            Variable latLonVar = netcdf.addVariable(gridMapping, DataType.INT, "");
            // this whole section will only work if gridMapping == Latitude_Longitude
            netcdf.addVariableAttribute(latLonVar, new Attribute("grid_mapping_name", "latitude_longitude"));
            netcdf.addVariableAttribute(latLonVar, new Attribute("semi_major_axis", 6378137.0));
            netcdf.addVariableAttribute(latLonVar, new Attribute("semi_minor_axis", 6356752.314245));
            netcdf.addVariableAttribute(latLonVar, new Attribute("longitude_of_prime_meridian", 0));
            
            latVar = netcdf.addVariable("lat", DataType.DOUBLE, "y x");
            netcdf.addVariableAttribute(latVar, new Attribute("units", "degrees_north"));
            netcdf.addVariableAttribute(latVar, new Attribute("long_name", "Latitude"));
            netcdf.addVariableAttribute(latVar, new Attribute("standard_name", "latitude"));
            
            lonVar = netcdf.addVariable("lon", DataType.DOUBLE, "y x");
            netcdf.addVariableAttribute(lonVar, new Attribute("units", "degrees_east"));
            netcdf.addVariableAttribute(lonVar, new Attribute("long_name", "Longitude"));
            netcdf.addVariableAttribute(lonVar, new Attribute("standard_name", "longitude"));
        }
        
        List<String> varExcludes = Lists.newArrayList();
        varExcludes.addAll(excludes.get(VAR));
        varExcludes.addAll(excludes.get(XY));
        for (Variable var : srcNc.getVariables()) {
            if (varExcludes.contains(var.getFullName())) {
                // hold this var out
            }
            else {
                Variable newVar = netcdf.addVariable(var.getFullName(), var.getDataType(), var.getDimensionsString());

                for (Attribute varAttr : var.getAttributes()) {
                    netcdf.addVariableAttribute(newVar, varAttr);
                }
                // again this will pretty much just work for this case
                // adding a standard name is not a bad idea
                netcdf.addVariableAttribute(newVar, new Attribute("grid_mapping", gridMapping));
                netcdf.addVariableAttribute(newVar, new Attribute("coordinates", "lon lat"));
            }
        }
        
        for (Attribute attr : srcNc.getGlobalAttributes()) {
            netcdf.addGlobalAttribute(attr);
        }
        netcdf.addGlobalAttribute(new Attribute("Conventions", "CF-1.6"));
        
        netcdf.create();
        writeLatsAndLons(latVar, lonVar);
    }
    
    private void writeLatsAndLons(Variable latVar, Variable lonVar) throws FactoryException, TransformException, IOException, InvalidRangeException {
        checkDefined();
        double[] xCoords = getXCoords();
        double[] yCoords = getYCoords();
        double[] latLonPairs = transformToLatLon(xCoords, yCoords);
        ArrayDouble.D2 dataLat = new ArrayDouble.D2(yCoords.length, xCoords.length);
        ArrayDouble.D2 dataLon = new ArrayDouble.D2(yCoords.length, xCoords.length);
        int yIndex = 0;
        int xIndex = 0;
        for (int i=0; i<latLonPairs.length; i+=2) {
            double lon = latLonPairs[i];
            double lat = latLonPairs[i+1];

            dataLat.set(yIndex, xIndex, lat);
            dataLon.set(yIndex, xIndex, lon);
            if (++xIndex % xCoords.length == 0) {
                xIndex = 0;
                yIndex++;
            }
        }
        netcdf.write(latVar.getFullNameEscaped(), dataLat);
        netcdf.write(lonVar.getFullNameEscaped(), dataLon);
    }
    
    private void checkDefined() {
        if (netcdf == null || gridDs == null || crs == null || gdt == null) {
            throw new UnsupportedOperationException("Must define prototype before continuing");
        }
    }
    
    private FeatureDataset getFeatureDatasetFromFile(File grib) throws IOException {
        FeatureDataset dataset = FeatureDatasetFactoryManager.open(
                FeatureType.ANY, grib.getAbsolutePath(), null, null);
        return dataset;
    }
    
    private GridDataset getGridDatasetFromFeatureDataset(FeatureDataset dataset) {
        if (dataset != null && dataset instanceof GridDataset) {
            return (GridDataset)dataset;
        }
        throw new UnsupportedOperationException("Dataset must be of type: GRID");
    }
    
    private GridDatatype getDatatypeFromDataset(GridDataset gridDataset) {
        if (gridDataset != null) {
            List<GridDatatype> gdts = gridDataset.getGrids();
            if (!gdts.isEmpty()) {
                return gdts.get(0);
            }
        }
        throw new UnsupportedOperationException("Not a valid gridDataset");
    }
    
    private CoordinateReferenceSystem getCRSFromDatatype(GridDatatype gridDatatype) {
        GridCoordSystem coordinateSystem = gridDatatype.getCoordinateSystem();
        return CRSUtility.getCRSFromGridCoordSystem(coordinateSystem);
    } 
    
    public NetcdfDataset getNetcdfFromGrib(File gribIn) throws IOException {
        FeatureDataset featureDataset = getFeatureDatasetFromFile(gribIn);
        GridDataset gds = getGridDatasetFromFeatureDataset(featureDataset);
        return gds.getNetcdfDataset();
    }

    public GridDataset getDataset() {
        checkDefined();
        return gridDs;
    }

    public CoordinateReferenceSystem getCRS() {
        checkDefined();
        return crs;
    }
    
    public double[][] transformToLatLonNetCDFStyle(double[] xCoords, double[] yCoords) {
        checkDefined();
        double[][] from = new double[2][xCoords.length * yCoords.length];
        for (int x=0; x<xCoords.length; x++) {
            for (int y=0; y<yCoords.length; y++) {
                from[0][x*yCoords.length+y] = xCoords[x];
                from[1][x*yCoords.length+y] = yCoords[y];
            }
        }
        ProjectionImpl projection = gdt.getCoordinateSystem().getProjection();
        double[][] projToLatLon = projection.projToLatLon(from);
        return projToLatLon;
    }
    
    public double[] transformToLatLon(double[] xCoords, double[] yCoords) throws FactoryException, TransformException {
        checkDefined();
        double[] transformArray = new double[2 * xCoords.length * yCoords.length];
        int i=0;
        for (int y=0; y<yCoords.length; y++) {
            for (int x=0; x<xCoords.length; x++) {
                transformArray[i++] = xCoords[x];
                transformArray[i++] = yCoords[y];
            }
        }
        
        MathTransform toWGS84Transform = CRS.findMathTransform(
                crs,
                DefaultGeographicCRS.WGS84,
                true);  // must be true if missing bursa-wolf parameters (akak TOWGS84[...])
        
        toWGS84Transform.transform(transformArray, 0, transformArray, 0, transformArray.length / 2);
        return transformArray;
    }

    public double[] getXCoords() {
        checkDefined();
        NetcdfDataset nc = gridDs.getNetcdfDataset();
        CoordinateAxis axis = nc.findCoordinateAxis(AxisType.GeoX);
        if (axis instanceof CoordinateAxis1D) {
            CoordinateAxis1D x = (CoordinateAxis1D)axis;
            double[] coordValues = x.getCoordValues();
            return coordValues;
        }
        throw new RuntimeException("Must contain 1D GeoX axis type");
    }

    public double[] getYCoords() {
        checkDefined();
        NetcdfDataset nc = gridDs.getNetcdfDataset();
        CoordinateAxis axis = nc.findCoordinateAxis(AxisType.GeoY);
        if (axis instanceof CoordinateAxis1D) {
            CoordinateAxis1D y = (CoordinateAxis1D)axis;
            double[] coordValues = y.getCoordValues();
            return coordValues;
        }
        throw new RuntimeException("Must contain 1D GeoX axis type");
    }

    public void addFile(File gribOrSomething) throws IOException, InvalidRangeException {
        // make GridDataset out of it
        checkDefined();
        FeatureDataset fd = getFeatureDatasetFromFile(gribOrSomething);
        GridDataset dataset = null;
        try {
            dataset = getGridDatasetFromFeatureDataset(fd);
            for (String varname : gridVariables) {
                GridDatatype grid = dataset.findGridDatatype(varname);
                GridCoordSystem gcs = grid.getCoordinateSystem();
                int xAxisLength = GridUtility.getXAxisLength(gcs);
                int yAxisLength = GridUtility.getYAxisLength(gcs);
                CoordinateAxis1DTime timeAxis1D = gcs.getTimeAxis1D();
                int[] origins = new int[3];
                for (int i=0; i<timeAxis1D.getSize(); i++) {
                    origins[0] = i;
                    CalendarDate calendarDate = timeAxis1D.getCalendarDate(i);
                    Array slice = grid.readDataSlice(i, -1, -1, -1);
                    netcdf.write(grid.getFullName(), origins, slice);
                    calendarDate.toDate(); // need to write date to unlimited dimension
                }
                

            }
        }
        finally {
            dataset.close();
        }
    }
    
    public void finalize() throws IOException {
        netcdf.setRedefineMode(true);
        netcdf.getUnlimitedDimension().setUnlimited(false);
        netcdf.setRedefineMode(false);
        close();
    }
    
    @Override
    public void flush() throws IOException {
        netcdf.flush();
    }
    
    @Override
    public void close() throws IOException {
        netcdf.close();
    }
}
