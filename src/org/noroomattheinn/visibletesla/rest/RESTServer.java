/*
 * RESTServer.java - Copyright(c) 2014 Joe Pasqua
 * Provided under the MIT License. See the LICENSE file for details.
 * Created: May 24, 2014
 */

package org.noroomattheinn.visibletesla.rest;

import com.sun.net.httpserver.BasicAuthenticator;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import static org.noroomattheinn.tesla.Tesla.logger;
import org.noroomattheinn.utils.Utils;
import org.noroomattheinn.visibletesla.MessageTemplate;
import org.noroomattheinn.utils.LRUMap;
import org.noroomattheinn.utils.ThreadManager;
import org.noroomattheinn.visibletesla.AppAPI;
import org.noroomattheinn.visibletesla.vehicle.VTVehicle;

/**
 * RESTServer: Provide minimal external services.
 * 
 * @author Joe Pasqua <joe at NoRoomAtTheInn dot org>
 */
public class RESTServer implements ThreadManager.Stoppable {
    
/*------------------------------------------------------------------------------
 *
 * Constants and Enums
 * 
 *----------------------------------------------------------------------------*/

    private static final Map<String,AppAPI.Mode> toAppMode = 
            Utils.newHashMap("sleep",   AppAPI.Mode.AllowSleeping,
                             "wakeup",  AppAPI.Mode.StayAwake,
                             "produce", AppAPI.Mode.StayAwake);

/*------------------------------------------------------------------------------
 *
 * Internal State
 * 
 *----------------------------------------------------------------------------*/
    
    private final AppAPI api;
    private final VTVehicle vtVehicle;
    private final BasicAuthenticator authenticator;
    private final BooleanProperty restEnabled;
    private final IntegerProperty restPort;
    private final StringProperty urlSource;
    
    private HttpServer server;
    private boolean launched = false;
    
/*==============================================================================
 * -------                                                               -------
 * -------              Public Interface To This Class                   ------- 
 * -------                                                               -------
 *============================================================================*/
    
    public RESTServer(
            AppAPI api, VTVehicle v, BasicAuthenticator authenticator,
            BooleanProperty restEnabled, IntegerProperty restPort,
            StringProperty urlSource) {
        this.server = null;
        this.api = api;
        this.vtVehicle = v;
        this.restEnabled = restEnabled;
        this.urlSource = urlSource;
        this.restPort = restPort;
        this.authenticator = authenticator;
        ThreadManager.get().addStoppable((ThreadManager.Stoppable)this);
        watch(v);
    }
    
    @Override public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

/*------------------------------------------------------------------------------
 *
 * Methods to launch the server when a vehicle comes into being
 * 
 *----------------------------------------------------------------------------*/
        
    private void watch(final VTVehicle v) {
        v.vehicle.addTracker(new Runnable() {
            @Override public void run() {
                if (v.vehicle.get() != null && !launched) {
                    launch();
                    launched = true;
                }
            }
        });
    }
    
    private synchronized void launch() {
        if (!restEnabled.get()) {
            logger.info("REST Services are disabled");
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(restPort.get()), 0);
            
            HttpContext cc;
            cc = server.createContext("/v1/action/activity", activityRequest);
            cc.setAuthenticator(authenticator);
            cc = server.createContext("/v1/action/info", infoRequest);
            cc.setAuthenticator(authenticator);
            cc = server.createContext("/", staticPageRequest);
            cc.setAuthenticator(authenticator);

            server.setExecutor(null); // creates a default executor
            server.start();
        } catch (IOException ex) {
            logger.severe("Unable to start RESTServer: " + ex.getMessage());
        }
    }


/*------------------------------------------------------------------------------
 *
 * PRIVATE - Request Handlers
 * 
 *----------------------------------------------------------------------------*/
    
    private HttpHandler activityRequest = new HttpHandler() {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendResponse(exchange, 400, "GET only on activity endpoint\n");
                return;
            }
            String path = StringUtils.stripEnd(exchange.getRequestURI().getPath(), "/");
            String mode = StringUtils.substringAfterLast(path, "/");
            if (mode.equals("activity")) {
                sendResponse(exchange, 403, "403 (Forbidden)\n");
                return;
            }
            AppAPI.Mode requestedMode = toAppMode.get(mode);
            if (requestedMode == null) {
                logger.warning("Unknown app mode: " + mode + "\n");
                sendResponse(exchange, 400, "Unknown app mode");
                return;
            }
            logger.info("Requested app mode: " + mode);
            if (requestedMode == AppAPI.Mode.AllowSleeping) api.allowSleeping();
            else api.stayAwake();

            sendResponse(exchange, 200,  "Requested mode: " + mode + "\n");
        }
    };

    private HttpHandler infoRequest = new HttpHandler() {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendResponse(exchange, 400, "GET only on info endpoint\n");
                return;
            }
            String path = StringUtils.stripEnd(exchange.getRequestURI().getPath(), "/");
            String infoType = StringUtils.substringAfterLast(path, "/");
            if (infoType.equals("info")) {
                sendResponse(exchange, 403, "403 (Forbidden)\n");
                return;
            }
            logger.info("Requested info type: " + infoType);
            String response;
            switch (infoType) {
                case "car_state":
                    response = vtVehicle.carStateAsJSON();
                    break;
                case "car_details":
                    response = vtVehicle.carDetailsAsJSON();
                    break;
                case "inactivity_mode":
                    response = String.format("{ \"mode\": \"%s\" }", api.mode.get().name());
                    break;
                case "dbg_sar":
                    Map<String,String> params = getParams(exchange.getRequestURI().getQuery());
                    response = params.get("p1");
                    if (response == null) { response = "DBG_SAR"; }
                    api.fakeSchedulerActivity(response);
                    break;
                default:
                    logger.warning("Unknown info request: " + infoType + "\n");
                    sendResponse(exchange, 400, "Unknown info request " + infoType);
                    return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            sendResponse(exchange, 200,  response);
        }
    };

    private Map<String, String> getParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String pair[] = param.split("=");
                if (pair.length > 1) {
                    params.put(pair[0], pair[1]);
                } else {
                    params.put(pair[0], "");
                }
            }
        }
        return params;
    }
    
    private HttpHandler staticPageRequest = new HttpHandler() {
        LRUMap<String,byte[]> cache = new LRUMap<>(10);
        @Override public void handle(HttpExchange exchange) throws IOException {
            // TO DO: Check for path traversal attack!
            String path = StringUtils.stripEnd(exchange.getRequestURI().getPath(), "/");
            path = StringUtils.stripStart(path, "/");
            try {
                byte[] content = cache.get(path);
                if (content == null) {
                    InputStream is;
                    if (path.startsWith("custom/")) {
                        String cPath = path.substring(7);
                        is = new URL(urlSource.get()+cPath).openStream();
                    } else if (path.startsWith("TeslaResources/")) {
                        path = "org/noroomattheinn/" + path;
                        is = getClass().getClassLoader().getResourceAsStream(path);
                    } else {
                        is = getClass().getResourceAsStream(path);
                    }

                    if (is == null) {
                        sendResponse(exchange, 404, "404 (Not Found)\n");
                        return;
                    } else {
                        content = IOUtils.toByteArray(is);
                        if (!path.startsWith("custom/_nc_"))
                            cache.put(path, content);
                    }
                }
                
                String type = getMimeType(StringUtils.substringAfterLast(path, "."));
                if (type.equalsIgnoreCase("text/html")) {
                    MessageTemplate mt = new MessageTemplate(new String(content, "UTF-8"));
                    content = mt.getMessage(api, vtVehicle, null).getBytes();
                } else if (cacheOnClient(type)) {
                    exchange.getResponseHeaders().add("Cache-Control", "max-age=2592000");
                }

                exchange.getResponseHeaders().add("Content-Type", type);
                sendResponse(exchange, 200, content);
            } catch (IOException ex) {
                logger.severe("Error reading requested file: " + ex.getMessage());
                sendResponse(exchange, 404, "404 (Not Found)\n");
            }
        }
    };
    

/*------------------------------------------------------------------------------
 *
 * PRIVATE - Utility Methods
 * 
 *----------------------------------------------------------------------------*/

    private boolean cacheOnClient(String type) {
        return (!type.equals("text/html"));
    }
    
    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        exchange.sendResponseHeaders(code, response.length());
        OutputStream os = exchange.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }
    
    private void sendResponse(HttpExchange exchange, int code, byte[] response) throws IOException {
        exchange.sendResponseHeaders(code, response.length);
        OutputStream os = exchange.getResponseBody();
        os.write(response);
        os.close();
    }
    
    private String getMimeType(String type) {
        if (type != null) {
            switch (type) {
                case "css": return "text/css";
                case "htm":
                case "html": return "text/html";
                case "js": return "application/javascript";
                case "png": return "image/png";
                case "gif": return "image/gif";
                case "jpg":
                case "jpeg": return "image/jpeg";
            }
        }
        return "text/plain";
    }
}