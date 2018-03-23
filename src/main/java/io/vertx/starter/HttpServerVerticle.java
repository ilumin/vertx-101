package io.vertx.starter;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

public class HttpServerVerticle extends AbstractVerticle {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);
  private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

  private static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
  private static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

  private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n\nFeel-free to write in Markdown!\n";

  private String wikiDbQueue = "wikidb.queue";
  private int portNumber = 8080;

  @Override
  public void start(Future<Void> startFuture) throws Exception {
    wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, wikiDbQueue);

    HttpServer server = vertx.createHttpServer();

    Router router = Router.router(vertx);
    router.get("/").handler(this::indexHandler);
    router.get("/wiki/:page").handler(this::pageRenderingHandler);
    router.post().handler(BodyHandler.create());
    router.post("/wiki").handler(this::pageCreateHandler);
    router.post("/wiki/:page").handler(this::pageUpdateHandler);
    router.post("/delete/:id").handler(this::pageDeletionHandler);

    portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, portNumber);
    server
      .requestHandler(router::accept)
      .listen(portNumber, ar -> {
        if (ar.succeeded()) {
          LOGGER.info("HTTP server running on port " + portNumber);
          startFuture.complete();
        } else {
          LOGGER.error("Could not start a HTTP server", ar.cause());
          startFuture.fail(ar.cause());
        }
      });
  }

  private void pageDeletionHandler(RoutingContext context) {
    //
  }

  private void pageUpdateHandler(RoutingContext context) {
    String title = context.request().getParam("title");
    JsonObject request = new JsonObject()
      .put("id", context.request().getParam("id"))
      .put("title", title)
      .put("markdown", context.request().getParam("markdown"));

    DeliveryOptions options = new DeliveryOptions();
    if ("yes".equals(context.request().getParam("newPage"))) {
      options.addHeader("action", "create-page");
    } else {
      options.addHeader("action", "save-page");
    }

    vertx
      .eventBus()
      .send(wikiDbQueue, request, options, reply -> {
        if (reply.succeeded()) {
          redirect(context, "/wiki/" + title, 303);
        } else {
          context.fail(reply.cause());
        }
      });
  }

  private void pageCreateHandler(RoutingContext context) {
    //
  }

  private void pageRenderingHandler(RoutingContext context) {
    String requestedPage = context.request().getParam("page");
    JsonObject request = new JsonObject().put("page", requestedPage);

    DeliveryOptions options = new DeliveryOptions().addHeader("action", "get-page");

    vertx
      .eventBus()
      .send(wikiDbQueue, request, options, reply -> {
        if (reply.succeeded()) {
          JsonObject body = (JsonObject) reply.result().body();
          boolean found = body.getBoolean("found");
          String rawContent = body.getString("rawContent", EMPTY_PAGE_MARKDOWN);

          context.put("title", requestedPage);
          context.put("id", body.getInteger("id", -1));
          context.put("newPage", found ? "no" : "yes");
          context.put("rawContent", rawContent);
          context.put("content", Processor.process(rawContent));
          context.put("timestamp", new Date().toString());

          renderTemplate(context, "templates", "/page.ftl");
        } else {
          context.fail(reply.cause());
        }
      });
  }

  private void indexHandler(RoutingContext context) {
    DeliveryOptions options = new DeliveryOptions().addHeader("action", "all-pages");

    vertx
      .eventBus()
      .send(wikiDbQueue, new JsonObject(), options, reply -> {
        if (reply.succeeded()) {
          JsonObject body = (JsonObject) reply.result().body();
          context.put("title", "Wiki home");
          context.put("pages", body.getJsonArray("pages").getList());

          renderTemplate(context, "templates", "/index.ftl");
        } else {
          context.fail(reply.cause());
        }
      });
  }

  private void renderTemplate(RoutingContext context, String templateDir, String templateFile) {
    templateEngine.render(context, templateDir, templateFile, ar -> {
      if (ar.succeeded()) {
        context.response().putHeader("Content-Type", "text/html");
        context.response().end(ar.result());
      } else {
        context.fail(ar.cause());
      }
    });
  }

  private void redirect(RoutingContext context, String path, int statusCode) {
    context.response().setStatusCode(statusCode);
    context.response().putHeader("Location", path);
    context.response().end();
  }

}
