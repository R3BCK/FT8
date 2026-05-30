package com.bg7yoz.ft8cn.maidenhead;
/**
 * Maidenhead grid processing. Includes lat/lon conversion, distance and azimuth calculation.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;
import com.google.android.gms.maps.model.LatLng;

import java.util.List;
import java.util.Locale;

public class MaidenheadGrid {
    private static final String TAG = "MaidenheadGrid";
    private static final double EARTH_RADIUS = 6371393; // Mean radius in meters

    /**
     * Convert Maidenhead grid to LatLng (center of square).
     * Supports 2, 4 or 6 character grids. Returns null if invalid.
     *
     * @param grid Maidenhead grid string
     * @return LatLng object or null if conversion failed
     */
    public static LatLng gridToLatLng(String grid) {
        if (grid == null || grid.length() == 0) return null;
        if (grid.length() != 2 && grid.length() != 4 && grid.length() != 6) {
            return null;
        }
        if (grid.equalsIgnoreCase("RR73") || grid.equalsIgnoreCase("RR")) return null;

        double x = 0, y = 0, z = 0;
        double lat = 0;

        // Latitude calculation
        if (grid.length() == 2) {
            x = grid.toUpperCase().charAt(1) - 'A' + 0.5f;
        } else {
            x = grid.toUpperCase().charAt(1) - 'A';
        }
        x *= 10;

        if (grid.length() == 4) {
            y = grid.charAt(3) - '0' + 0.5f;
        } else if (grid.length() == 6) {
            y = grid.charAt(3) - '0';
        }

        if (grid.length() == 6) {
            z = grid.toUpperCase().charAt(5) - 'A' + 0.5f;
            z = z * (1f / 18f);
        }
        lat = x + y + z - 90;

        // Longitude calculation
        x = 0; y = 0; z = 0;
        double lng = 0;
        if (grid.length() == 2) {
            x = grid.toUpperCase().charAt(0) - 'A' + 0.5;
        } else {
            x = grid.toUpperCase().charAt(0) - 'A';
        }
        x *= 20;
        if (grid.length() == 4) {
            y = grid.charAt(2) - '0' + 0.5;
        } else if (grid.length() == 6) {
            y = grid.charAt(2) - '0';
        }
        y *= 2;
        if (grid.length() == 6) {
            z = grid.toUpperCase().charAt(4) - 'A' + 0.5;
            z = z * (2f / 18f);
        }
        lng = x + y + z - 180;

        // Clamp latitude to valid map range
        if (lat > 85) lat = 85;
        if (lat < -85) lat = -85;

        return new LatLng(lat, lng);
    }

    /**
     * Convert Maidenhead grid to polygon (4 corner points).
     * @param grid Maidenhead grid string
     * @return Array of 4 LatLng corners or null if invalid
     */
    public static LatLng[] gridToPolygon(String grid) {
        if (grid.length() != 2 && grid.length() != 4 && grid.length() != 6) {
            return null;
        }
        LatLng[] latLngs = new LatLng[4];

        // Latitude 1
        double x = grid.toUpperCase().charAt(1) - 'A';
        x *= 10;
        double y = 0, z = 0;
        if (grid.length() > 2) {
            y = grid.charAt(3) - '0';
        }
        if (grid.length() > 4) {
            z = grid.toUpperCase().charAt(5) - 'A';
            z = z * (1f / 18f);
        }
        double lat1 = x + y + z - 90;
        lat1 = Math.max(-85.0, Math.min(85.0, lat1));

        // Latitude 2
        x = 0; y = 0; z = 0;
        if (grid.length() == 2) {
            x = grid.toUpperCase().charAt(1) - 'A' + 1;
        } else {
            x = grid.toUpperCase().charAt(1) - 'A';
        }
        x *= 10;
        if (grid.length() == 4) {
            y = grid.charAt(3) - '0' + 1;
        } else if (grid.length() == 6) {
            y = grid.charAt(3) - '0';
        }
        if (grid.length() == 6) {
            z = grid.toUpperCase().charAt(5) - 'A' + 1;
            z = z * (1f / 18f);
        }
        double lat2 = x + y + z - 90;
        lat2 = Math.max(-85.0, Math.min(85.0, lat2));

        // Longitude 1
        x = 0; y = 0; z = 0;
        x = grid.toUpperCase().charAt(0) - 'A';
        x *= 20;
        if (grid.length() > 2) {
            y = grid.charAt(2) - '0';
            y *= 2;
        }
        if (grid.length() > 4) {
            z = grid.toUpperCase().charAt(4) - 'A';
            z = z * 2f / 18f;
        }
        double lng1 = x + y + z - 180;

        // Longitude 2
        x = 0; y = 0; z = 0;
        if (grid.length() == 2) {
            x = grid.toUpperCase().charAt(0) - 'A' + 1;
        } else {
            x = grid.toUpperCase().charAt(0) - 'A';
        }
        x *= 20;
        if (grid.length() == 4) {
            y = grid.charAt(2) - '0' + 1;
        } else if (grid.length() == 6) {
            y = grid.charAt(2) - '0';
        }
        y *= 2;
        if (grid.length() == 6) {
            z = grid.toUpperCase().charAt(4) - 'A' + 1;
            z = z * 2f / 18f;
        }
        double lng2 = x + y + z - 180;

        latLngs[0] = new LatLng(lat1, lng1);
        latLngs[1] = new LatLng(lat1, lng2);
        latLngs[2] = new LatLng(lat2, lng2);
        latLngs[3] = new LatLng(lat2, lng1);

        return latLngs;
    }

    /**
     * Generate 6-character Maidenhead grid from LatLng.
     * @param location LatLng with latitude/longitude
     * @return 4-character grid string (extended to 6 if needed)
     */
    public static String getGridSquare(LatLng location) {
        double tempNumber;
        int index;
        double _long = location.longitude;
        double _lat = location.latitude;
        StringBuilder buff = new StringBuilder();

        // First pair: uppercase letters
        _long += 180;
        tempNumber = _long / 20;
        index = (int) tempNumber;
        buff.append((char) (index + 'A'));
        _long = _long - (index * 20);

        _lat += 90;
        tempNumber = _lat / 10;
        index = (int) tempNumber;
        buff.append((char) (index + 'A'));
        _lat = _lat - (index * 10);

        // Second pair: digits
        tempNumber = _long / 2;
        index = (int) tempNumber;
        buff.append((char) (index + '0'));
        _long = _long - (index * 2);

        tempNumber = _lat;
        index = (int) tempNumber;
        buff.append((char) (index + '0'));
        _lat = _lat - index;

        // Third pair: lowercase letters (calculated but truncated to 4 chars)
        tempNumber = _long / 0.083333;
        index = (int) tempNumber;
        buff.append((char) (index + 'a'));

        tempNumber = _lat / 0.0416665;
        index = (int) tempNumber;
        buff.append((char) (index + 'a'));

        return buff.toString().substring(0, 4);
    }

    /**
     * Calculate great-circle distance between two LatLng points.
     * @param latLng1 First point
     * @param latLng2 Second point
     * @return Distance in kilometers
     */
    public static double getDist(LatLng latLng1, LatLng latLng2) {
        double radiansAX = Math.toRadians(latLng1.longitude);
        double radiansAY = Math.toRadians(latLng1.latitude);
        double radiansBX = Math.toRadians(latLng2.longitude);
        double radiansBY = Math.toRadians(latLng2.latitude);

        double cos = Math.cos(radiansAY) * Math.cos(radiansBY) * Math.cos(radiansAX - radiansBX)
                + Math.sin(radiansAY) * Math.sin(radiansBY);
        double acos = Math.acos(cos);
        return EARTH_RADIUS * acos / 1000;
    }

    /**
     * Calculate distance between two Maidenhead grids.
     * @param mGrid1 First grid
     * @param mGrid2 Second grid
     * @return Distance in kilometers, or 0 if conversion failed
     */
    public static double getDist(String mGrid1, String mGrid2) {
        LatLng latLng1 = gridToLatLng(mGrid1);
        LatLng latLng2 = gridToLatLng(mGrid2);
        if (latLng1 != null && latLng2 != null) {
            return getDist(latLng1, latLng2);
        } else {
            return 0;
        }
    }

    /**
     * Calculate distance string with localized format.
     * @param mGrid1 First grid
     * @param mGrid2 Second grid
     * @return Formatted distance string or empty if failed
     */
    @SuppressLint("DefaultLocale")
    public static String getDistStr(String mGrid1, String mGrid2) {
        double dist = getDist(mGrid1, mGrid2);
        if (dist == 0) {
            return "";
        } else {
            return String.format(GeneralVariables.getStringFromResource(R.string.distance), dist);
        }
    }

    public static String getDistLatLngStr(LatLng latLng1, LatLng latLng2){
        return String.format(GeneralVariables.getStringFromResource(R.string.distance), getDist(latLng1, latLng2));
    }

    /**
     * Calculate distance string in English format (XX km).
     * @param mGrid1 First grid
     * @param mGrid2 Second grid
     * @return Formatted distance string or empty if failed
     */
    @SuppressLint("DefaultLocale")
    public static String getDistStrEN(String mGrid1, String mGrid2) {
        double dist = getDist(mGrid1, mGrid2);
        if (dist == 0) {
            return "";
        } else {
            return String.format(Locale.US, "%.0f km", dist);
        }
    }

    /**
     * Calculate azimuth (bearing) from my grid to target grid using great circle formula.
     * @param myGrid My Maidenhead grid (e.g. KO87)
     * @param targetGrid Target Maidenhead grid (e.g. JO31)
     * @return Azimuth in degrees 0-360, or -1 if calculation failed
     */
    public static double getAzimuth(String myGrid, String targetGrid) {
        if (myGrid == null || targetGrid == null || myGrid.length() < 4 || targetGrid.length() < 4) {
            return -1;
        }

        double[] myCoords = gridToLatLon(myGrid);
        double[] targetCoords = gridToLatLon(targetGrid);

        if (myCoords == null || targetCoords == null) {
            return -1;
        }

        return calculateAzimuth(myCoords[0], myCoords[1], targetCoords[0], targetCoords[1]);
    }

    /**
     * Convert Maidenhead grid to latitude/longitude center of square.
     * @param grid Maidenhead grid (min 4 characters)
     * @return Array [lat, lon] in degrees, or null if invalid
     */
    private static double[] gridToLatLon(String grid) {
        if (grid == null || grid.length() < 4) return null;

        grid = grid.toUpperCase(Locale.US).trim();

        // Field: 2 letters A-R = -180..+180 lon, -90..+90 lat
        int lonField = grid.charAt(0) - 'A';
        int latField = grid.charAt(1) - 'A';

        // Square: 2 digits 0-9 = 20 deg lon, 10 deg lat
        int lonSquare = grid.charAt(2) - '0';
        int latSquare = grid.charAt(3) - '0';

        // Calculate center of square
        double lon = -180 + lonField * 20 + lonSquare * 2 + 1; // +1 for center
        double lat = -90 + latField * 10 + latSquare * 1 + 0.5; // +0.5 for center

        return new double[]{lat, lon};
    }

    /**
     * Calculate initial bearing (azimuth) between two lat/lon points using great circle formula.
     * Formula: atan2(sin(dLon)*cos(lat2), cos(lat1)*sin(lat2) - sin(lat1)*cos(lat2)*cos(dLon))
     * @param lat1 Latitude of point 1 in degrees
     * @param lon1 Longitude of point 1 in degrees
     * @param lat2 Latitude of point 2 in degrees
     * @param lon2 Longitude of point 2 in degrees
     * @return Azimuth in degrees 0-360
     */
    private static double calculateAzimuth(double lat1, double lon1, double lat2, double lon2) {
        // Convert to radians
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLonRad = Math.toRadians(lon2 - lon1);

        // Great circle bearing formula
        double x = Math.cos(lat2Rad) * Math.sin(deltaLonRad);
        double y = Math.cos(lat1Rad) * Math.sin(lat2Rad)
                - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(deltaLonRad);

        double bearingRad = Math.atan2(x, y);
        double bearingDeg = Math.toDegrees(bearingRad);

        // Normalize to 0-360 degrees
        return (bearingDeg + 360) % 360;
    }

    /**
     * Get device location using Android LocationManager.
     * @param context Android context
     * @return LatLng of device or null if unavailable
     */
    public static LatLng getLocalLocation(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return null;

        List<String> providers = locationManager.getProviders(true);
        Location bestLocation = null;

        for (String provider : providers) {
            @SuppressLint("MissingPermission") Location loc = locationManager.getLastKnownLocation(provider);
            if (loc == null) continue;
            if (bestLocation == null || loc.getAccuracy() < bestLocation.getAccuracy()) {
                bestLocation = loc;
            }
        }

        if (bestLocation != null) {
            return new LatLng(bestLocation.getLatitude(), bestLocation.getLongitude());
        } else {
            return null;
        }
    }

    /**
     * Get device Maidenhead grid (requires location permission).
     * @param context Android context
     * @return 6-character Maidenhead grid string or empty if failed
     */
    public static String getMyMaidenheadGrid(Context context) {
        LatLng latLng = getLocalLocation(context);
        if (latLng != null) {
            return getGridSquare(latLng);
        } else {
            return "";
        }
    }

    /**
     * Validate if string is a valid Maidenhead grid.
     * @param s String to check
     * @return true if valid 4 or 6 character grid, false otherwise
     */
    public static boolean checkMaidenhead(String s) {
        if (s == null || (s.length() != 4 && s.length() != 6)) {
            return false;
        }
        if (s.equals("RR73")) {
            return false;
        }
        return Character.isLetter(s.charAt(0))
                && Character.isLetter(s.charAt(1))
                && Character.isDigit(s.charAt(2))
                && Character.isDigit(s.charAt(3));
    }
}