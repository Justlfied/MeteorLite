/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package meteor.callback;

import static meteor.MeteorLiteClientModule.mainWindow;
import static net.runelite.api.widgets.WidgetInfo.WORLD_MAP_VIEW;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import meteor.MeteorLiteClientModule;
import meteor.chat.ChatMessageManager;
import meteor.eventbus.DeferredEventBus;
import meteor.eventbus.EventBus;
import meteor.eventbus.Subscribe;
import meteor.input.KeyManager;
import meteor.input.MouseManager;
import meteor.task.Scheduler;
import meteor.ui.overlay.OverlayLayer;
import meteor.ui.overlay.OverlayRenderer;
import meteor.ui.overlay.infobox.InfoBoxManager;
import meteor.util.RSTimeUnit;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.RenderOverview;
import net.runelite.api.Renderable;
import net.runelite.api.Skill;
import net.runelite.api.WorldMapManager;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.hooks.Callbacks;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import org.sponge.util.Logger;

/**
 * This class contains field required for mixins and runelite hooks to work. All remaining method
 * hooks in this class are performance-critical or contain client-specific logic and so they can't
 * just be placed in mixins or sent through event bus.
 */
@Singleton
public class Hooks implements Callbacks {

  private static final long CHECK = RSTimeUnit.GAME_TICKS.getDuration()
      .toNanos(); // ns - how often to run checks
  private static final GameTick GAME_TICK = GameTick.INSTANCE;
  private static final BeforeRender BEFORE_RENDER = new BeforeRender();
  public static Logger log = new Logger("Hooks");
  private static Client client;
  private static MainBufferProvider lastMainBufferProvider;
  private static Graphics2D lastGraphics;
  private final OverlayRenderer renderer;
  private final EventBus eventBus;
  private final DeferredEventBus deferredEventBus;
  private final MouseManager mouseManager;
  private final ClientThread clientThread;
  private final KeyManager keyManager;
  private final InfoBoxManager infoBoxManager;
  private final ChatMessageManager chatMessageManager;
  private final Scheduler scheduler;
  private Dimension lastStretchedDimensions;
  private VolatileImage stretchedImage;
  private Graphics2D stretchedGraphics;
  private long lastCheck;
  private boolean ignoreNextNpcUpdate;
  private boolean shouldProcessGameTick;

  @Inject
  private Hooks(
      Client client,
      OverlayRenderer renderer,
      ClientThread clientThread,
      MouseManager mouseManager,
      EventBus eventBus,
      DeferredEventBus deferredEventBus,
      KeyManager keyManager,
      InfoBoxManager infoBoxManager,
      ChatMessageManager chatMessageManager,
      Scheduler scheduler
  ) {
    Hooks.client = client;
    this.clientThread = clientThread;
    this.renderer = renderer;
    this.mouseManager = mouseManager;
    this.eventBus = eventBus;
    this.deferredEventBus = deferredEventBus;
    this.keyManager = keyManager;
    this.infoBoxManager = infoBoxManager;
    this.chatMessageManager = chatMessageManager;
    this.scheduler = scheduler;
    eventBus.register(this);
  }

  /**
   * Get the Graphics2D for the MainBufferProvider image This caches the Graphics2D instance so it
   * can be reused
   *
   * @param mainBufferProvider
   * @return
   */
  private static Graphics2D getGraphics(MainBufferProvider mainBufferProvider) {
    if (lastGraphics == null || lastMainBufferProvider != mainBufferProvider) {
      if (lastGraphics != null) {
        lastGraphics.dispose();
      }

      lastMainBufferProvider = mainBufferProvider;
      lastGraphics = (Graphics2D) mainBufferProvider.getImage().getGraphics();
    }
    return lastGraphics;
  }

  /**
   * Copy an image
   *
   * @param src
   * @return
   */
  private static Image copy(Image src) {
    final int width = src.getWidth(null);
    final int height = src.getHeight(null);
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics graphics = image.getGraphics();
    graphics.drawImage(src, 0, 0, width, height, null);
    graphics.dispose();
    return image;
  }

  public static void clearColorBuffer(int x, int y, int width, int height, int color) {
    BufferProvider bp = client.getBufferProvider();
    int canvasWidth = bp.getWidth();
    int[] pixels = bp.getPixels();

    int pixelPos = y * canvasWidth + x;
    int pixelJump = canvasWidth - width;

    for (int cy = y; cy < y + height; cy++) {
      for (int cx = x; cx < x + width; cx++) {
        pixels[pixelPos++] = 0;
      }
      pixelPos += pixelJump;
    }
  }

  public static void renderDraw(Renderable renderable, int orientation, int pitchSin, int pitchCos,
      int yawSin, int yawCos, int x, int y, int z, long hash) {
    DrawCallbacks drawCallbacks = client.getDrawCallbacks();
    if (drawCallbacks != null) {
      drawCallbacks
          .draw(renderable, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);
    } else {
      renderable.draw$api(orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);
    }
  }

  public static boolean drawMenu() {
    BeforeMenuRender event = new BeforeMenuRender();
    client.getCallbacks().post(event);
    return event.isConsumed();
  }

  @Override
  public void post(Object event) {
    eventBus.post(event);
  }

  @Override
  public void postDeferred(Object event) {
    deferredEventBus.post(event);
  }

  @Override
  public void clientMainLoop() {
    if (shouldProcessGameTick) {
      shouldProcessGameTick = false;

      deferredEventBus.replay();

      eventBus.post(GAME_TICK);

      int tick = client.getTickCount();
      client.setTickCount(tick + 1);
    }

    eventBus.post(BEFORE_RENDER);

    clientThread.invoke();

    long now = System.nanoTime();

    if (now - lastCheck < CHECK) {
      return;
    }

    lastCheck = now;

    try {
      // tick pending scheduled tasks
      scheduler.tick();

      // cull infoboxes
      infoBoxManager.cull();

      chatMessageManager.process();

      checkWorldMap();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  /**
   * When the world map opens it loads about ~100mb of data into memory, which represents about half
   * of the total memory allocated by the client. This gets cached and never released, which causes
   * GC pressure which can affect performance. This method reinitializes the world map cache, which
   * allows the data to be garbage collected, and causes the map data from disk each time is it
   * opened.
   */
  private void checkWorldMap() {
    Widget widget = client.getWidget(WORLD_MAP_VIEW);

    if (widget != null) {
      return;
    }

    RenderOverview renderOverview = client.getRenderOverview();

    if (renderOverview == null) {
      return;
    }

    WorldMapManager manager = renderOverview.getWorldMapManager();

    if (manager != null && manager.isLoaded$api()) {
      renderOverview.initializeWorldMap(renderOverview.getWorldMapData());
    }
  }

  @Override
  public void draw(MainBufferProvider mainBufferProvider, Graphics graphics, int x, int y) {
    if (graphics == null) {
      return;
    }

    final Graphics2D graphics2d = getGraphics(mainBufferProvider);

    try {
      renderer.renderOverlayLayer(graphics2d, OverlayLayer.ALWAYS_ON_TOP);
    } catch (Exception ex) {
      log.warn("Error during overlay rendering");
      ex.printStackTrace();
    }

    if (client.isGpu()) {
      // processDrawComplete gets called on GPU by the gpu plugin at the end of its
      // drawing cycle, which is later on.
      return;
    }

    // Stretch the game image if the user has that enabled
    Image image = mainBufferProvider.getImage();
    final Image finalImage;
    if (client.isStretchedEnabled()) {
      GraphicsConfiguration gc = mainWindow.getGraphicsConfiguration();
      Dimension stretchedDimensions = client.getStretchedDimensions();

      if (lastStretchedDimensions == null || !lastStretchedDimensions.equals(stretchedDimensions)
          || (stretchedImage != null
          && stretchedImage.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE)) {
				/*
					Reuse the resulting image instance to avoid creating an extreme amount of objects
				 */
        stretchedImage = gc
            .createCompatibleVolatileImage(stretchedDimensions.width, stretchedDimensions.height);

        if (stretchedGraphics != null) {
          stretchedGraphics.dispose();
        }
        stretchedGraphics = (Graphics2D) stretchedImage.getGraphics();

        lastStretchedDimensions = stretchedDimensions;

				/*
					Fill Canvas before drawing stretched image to prevent artifacts.
				*/
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, client.getCanvas().getWidth(), client.getCanvas().getHeight());
      }

      stretchedGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
          client.isStretchedFast()
              ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
              : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      stretchedGraphics
          .drawImage(image, 0, 0, stretchedDimensions.width, stretchedDimensions.height, null);

      finalImage = stretchedImage;
    } else {
      finalImage = image;
    }

    // Draw the image onto the game canvas
    graphics.drawImage(finalImage, 0, 0, client.getCanvas());

    // finalImage is backed by the client buffer which will change soon. make a copy
    // so that callbacks can safely use it later from threads.
    //drawManager.processDrawComplete(() -> copy(finalImage));
  }

  @Override
  public void drawScene() {
    MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
    Graphics2D graphics2d = getGraphics(bufferProvider);

    try {
      renderer.renderOverlayLayer(graphics2d, OverlayLayer.ABOVE_SCENE);
    } catch (Exception ex) {
      log.warn("Error during overlay rendering");
      ex.printStackTrace();
    }
  }

  @Override
  public void drawAboveOverheads() {
    MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
    Graphics2D graphics2d = getGraphics(bufferProvider);

    try {
      renderer.renderOverlayLayer(graphics2d, OverlayLayer.UNDER_WIDGETS);
    } catch (Exception ex) {
      log.warn("Error during overlay rendering");
      ex.printStackTrace();
    }
  }

  @Subscribe
  public void onGameStateChanged(GameStateChanged gameStateChanged) {
    switch (gameStateChanged.getGameState()) {
      case LOGGING_IN:
      case HOPPING:
        ignoreNextNpcUpdate = true;
    }
  }

  @Override
  public void updateNpcs() {
    if (ignoreNextNpcUpdate) {
      // After logging in an NPC update happens outside of the normal game tick, which
      // is sent prior to skills and vars being bursted, so ignore it.
      ignoreNextNpcUpdate = false;
    } else {
      // The NPC update event seem to run every server tick,
      // but having the game tick event after all packets
      // have been processed is typically more useful.
      shouldProcessGameTick = true;
    }

    // Replay deferred events, otherwise if two npc
    // update packets get processed in one client tick, a
    // despawn event could be published prior to the
    // spawn event, which is deferred
    deferredEventBus.replay();
  }

  @Override
  public void drawInterface(int interfaceId, List<WidgetItem> widgetItems) {
    MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
    Graphics2D graphics2d = getGraphics(bufferProvider);

    try {
      renderer.renderAfterInterface(graphics2d, interfaceId, widgetItems);
    } catch (Exception ex) {
      log.warn("Error during overlay rendering");
      ex.printStackTrace();
    }
  }

  @Override
  public void drawLayer(Widget layer, List<WidgetItem> widgetItems) {
    MainBufferProvider bufferProvider = (MainBufferProvider) client.getBufferProvider();
    Graphics2D graphics2d = getGraphics(bufferProvider);

    try {
      renderer.renderAfterLayer(graphics2d, layer, widgetItems);
    } catch (Exception ex) {
      log.warn("Error during overlay rendering");
      ex.printStackTrace();
    }
  }

  @Override
  public MouseEvent mousePressed(MouseEvent mouseEvent) {
    return mouseManager.processMousePressed(mouseEvent);
  }

  @Override
  public MouseEvent mouseReleased(MouseEvent mouseEvent) {
    return mouseManager.processMouseReleased(mouseEvent);
  }

  @Override
  public MouseEvent mouseClicked(MouseEvent mouseEvent) {
    return mouseManager.processMouseClicked(mouseEvent);
  }

  @Override
  public MouseEvent mouseEntered(MouseEvent mouseEvent) {
    return mouseManager.processMouseEntered(mouseEvent);
  }

  @Override
  public MouseEvent mouseExited(MouseEvent mouseEvent) {
    return mouseManager.processMouseExited(mouseEvent);
  }

  @Override
  public MouseEvent mouseDragged(MouseEvent mouseEvent) {
    return mouseManager.processMouseDragged(mouseEvent);
  }

  @Override
  public MouseEvent mouseMoved(MouseEvent mouseEvent) {
    return mouseManager.processMouseMoved(mouseEvent);
  }

  @Override
  public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event) {
    return mouseManager.processMouseWheelMoved(event);
  }

  @Override
  public void keyPressed(KeyEvent keyEvent) {
    keyManager.processKeyPressed(keyEvent);
  }

  @Override
  public void keyReleased(KeyEvent keyEvent) {
    keyManager.processKeyReleased(keyEvent);
  }

  @Override
  public void keyTyped(KeyEvent keyEvent) {
    keyManager.processKeyTyped(keyEvent);
  }

  @Subscribe
  public void onScriptCallbackEvent(ScriptCallbackEvent scriptCallbackEvent) {
    if (!scriptCallbackEvent.getEventName().equals("fakeXpDrop")) {
      return;
    }

    final int[] intStack = client.getIntStack();
    final int intStackSize = client.getIntStackSize();

    final int statId = intStack[intStackSize - 2];
    final int xp = intStack[intStackSize - 1];

    Skill skill = Skill.values()[statId];
    FakeXpDrop fakeXpDrop = new FakeXpDrop(
        skill,
        xp
    );
    eventBus.post(fakeXpDrop);
  }
}
