package com.example.lanchat.web;

import com.google.gson.Gson;
import spark.Spark;

public class WebServer implements AutoCloseable {

    private final int port;
    private final ApiRoutes apiRoutes;
    private final Gson gson = new Gson();
    private volatile boolean started;

    public WebServer(int port, ApiRoutes apiRoutes) {
        this.port = port;
        this.apiRoutes = apiRoutes;
    }

    public void start() {
        if (started) return;
        started = true;

        Spark.port(port);
        Spark.staticFiles.location("/public");
        Spark.before((req, res) -> LocalOnlyFilter.enforce(req, res));

        Spark.exception(LocalOnlyFilter.LocalOnlyRejectedException.class, (e, req, res) -> {
            if (req.pathInfo() != null && req.pathInfo().startsWith("/api/")) {
                res.status(403);
                res.type("application/json");
                res.body(gson.toJson(Dto.fail("Localhost only")));
                return;
            }
            res.status(403);
            res.type("text/plain");
            res.body("Localhost only");
        });

        Spark.exception(Exception.class, (e, req, res) -> {
            if (req.pathInfo() != null && req.pathInfo().startsWith("/api/")) {
                res.status(500);
                res.type("application/json");
                res.body(gson.toJson(Dto.fail("Internal error")));
                return;
            }
            res.status(500);
            res.type("text/plain");
            res.body("Internal error");
        });

        Spark.notFound((req, res) -> {
            if (req.pathInfo() != null && req.pathInfo().startsWith("/api/")) {
                res.status(404);
                res.type("application/json");
                return gson.toJson(Dto.fail("Not found"));
            }
            res.type("text/plain");
            return "Not found";
        });

        apiRoutes.register();
        Spark.init();
        Spark.awaitInitialization();
    }

    @Override
    public void close() {
        if (!started) return;
        started = false;
        Spark.stop();
        Spark.awaitStop();
    }
}

