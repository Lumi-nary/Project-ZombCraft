package pzcraft.mc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pzcraft.protocol.CollisionSection;
import pzcraft.protocol.Ring;
import pzcraft.protocol.SharedLink;

/** Minecraft side of the PZ x Minecraft link. */
public class PzCraftClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("pzcraft");

	private static boolean optionsApplied;
	private static long teleportedAtTick = -1;
	private static long clientTicks;
	private static LocalPlayer lastPlayer;

	@Override
	public void onInitializeClient() {
		PzWeaponModels.register();   // pzcraft:pzmesh, PZ's own weapon meshes in the hands
		// the pistol's own crosshair (a gap that opens with movement and shots) instead of vanilla's cross
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.replaceElement(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR,
				original -> (graphics, tracker) -> { if (!GunFeel.drawCrosshair(graphics)) original.extractRenderState(graphics, tracker); });
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
				net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.HOTBAR,
				net.minecraft.resources.Identifier.parse("pzcraft:gun_ammo"), (graphics, tracker) -> GunFeel.drawAmmo(graphics));
		try {
			Session.link = SharedLink.open(SharedLink.Role.MC);
		} catch (Exception e) {
			LOG.error("Could not open shared link; PzCraft is inactive", e);
			return;
		}
		LOG.info("Shared link open at {} (pid {})", SharedLink.defaultPath(), ProcessHandle.current().pid());
		GpuPriority.raise();
		try {
			Session.frames = pzcraft.protocol.FrameLink.open();
		} catch (Exception e) {
			LOG.error("Could not open the frame link; no overlay", e);
		}

		Thread t = new Thread(PzCraftClient::linkLoop, "pzcraft-link");
		t.setDaemon(true);
		t.start();

		ActorProxies.init();
		PzGuns.init();
		TreeBridge.init();
		RespawnBridge.init();
		WeatherBridge.init();
		IntegratedSaveCommand.init();
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && player == Minecraft.getInstance().player) {
				LOG.info("use block: hand={} pos={} face={} item={}", hand, hit.getBlockPos(), hit.getDirection(), player.getItemInHand(hand));
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
		ClientTickEvents.START_CLIENT_TICK.register(InputBridge::apply);
		ClientTickEvents.END_CLIENT_TICK.register(PzCraftClient::onTick);
	}

	/** Minecraft closes with PZ unless told otherwise: -Dpzcraft.autoexit=false, or PZCRAFT_AUTOEXIT=0 in the environment. */
	private static boolean autoExit() {
		String v = System.getProperty("pzcraft.autoexit", System.getenv("PZCRAFT_AUTOEXIT"));
		return v == null || !(v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off"));
	}

	/** Heartbeat + event pump, independent of the game tick so the link stays alive on loading screens. */
	private static void linkLoop() {
		SharedLink l = Session.link;
		boolean wasAlive = false;
		boolean everConnected = false;
		long goneSince = 0;
		boolean autoExit = autoExit();
		long lastBeat = 0;
		while (true) {
			try {
				long now = System.currentTimeMillis();
				if (now - lastBeat >= 100) {
					l.heartbeat();
					lastBeat = now;
					boolean alive = l.peerAlive();
					if (alive != wasAlive) {
						LOG.info(alive ? "PZ connected (pid {})" : "PZ disconnected", l.peerPidOrZero());
						wasAlive = alive;
						Session.pzConnected = alive;
						TerrainCoverage.reset();
						CollisionField.reset();
						MaterialField.reset();
						if (!alive) CollisionField.vehicles(java.util.List.of());
						if (alive) everConnected = true;
						else goneSince = now;
					}
					if (autoExit && everConnected && !alive) {
						// PZ quit, crashed or was killed: follow it out; Minecraft saves the world as it stops. A PZ that
						// still runs but stopped beating gets longer. Counted from when it was missed, so a PC waking
						// from sleep is not taken for a quit.
						boolean quit = !l.peerProcessAlive();
						if (now - goneSince > (quit ? 3_000 : 20_000)) {
							LOG.info(quit ? "PZ has quit; closing Minecraft" : "PZ has been silent for 20 s; closing Minecraft");
							Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
							return;
						}
					}
				}
				if (Session.needPlacement) {
					Session.needPlacement = false;
					l.tx().write(SharedLink.MSG_NEED_PLACEMENT, new byte[0]);
				}
				if (Session.readyToAnnounce) {
					Session.readyToAnnounce = false;
					l.tx().write(SharedLink.MSG_READY, new byte[0]);
				}
				Object[] out;
				while ((out = Session.pollOutbox()) != null) {
					if (!l.tx().write((Integer) out[0], (byte[]) out[1])) break; // ring full: drop; PZ resyncs state anyway
				}
				Ring.Message m;
				while ((m = l.rx().poll()) != null) handle(l, m);
				Thread.sleep(2);
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				LOG.error("link loop error", t);
			}
		}
	}

	private static void handle(SharedLink l, Ring.Message m) {
		switch (m.type()) {
			case pzcraft.protocol.Wire.MSG_OBJECTS -> { InteractionBridge.faces = pzcraft.protocol.WorldObjects.faces(m.payload()); InteractionBridge.receivedAt = System.nanoTime(); }
			case pzcraft.protocol.Wire.MSG_CLOCK_STATE -> TimeBridge.state = pzcraft.protocol.WorldClock.State.decode(m.payload());
			case pzcraft.protocol.Wire.MSG_RESPAWN_READY -> RespawnBridge.acknowledged = m.buffer().getLong();
			case pzcraft.protocol.Wire.MSG_GUI_EVENT -> GuiBridge.enqueue(pzcraft.protocol.GuiEvent.decode(m.payload()));
			case SharedLink.MSG_PING -> l.tx().write(SharedLink.MSG_PONG, m.payload());
			case SharedLink.MSG_COLLISION -> CollisionField.put(CollisionSection.decode(m.payload()));
			case pzcraft.protocol.Wire.MSG_MATERIALS -> MaterialField.put(pzcraft.protocol.MaterialSection.decode(m.payload()));
				case pzcraft.protocol.Wire.MSG_CONTAINER_CONTENTS -> ContainerBridge.receive(m.payload());
			case pzcraft.protocol.Wire.MSG_MIRROR_HELLO -> MirrorBridge.hello();
			case pzcraft.protocol.Wire.MSG_MIRROR_TX -> MirrorBridge.receiveTransaction(m.payload());
			case pzcraft.protocol.Wire.MSG_GUN_RESULT -> PzGuns.receive(m.payload());
			case SharedLink.MSG_TERRAIN_COLUMN -> {
				ByteBuffer b=m.buffer(); TerrainCoverage.column(b.getInt(), b.getInt(), b.getInt()!=0);
			}
			case SharedLink.MSG_COLLISION_CLEAR -> {
				ByteBuffer b = m.buffer();
				int cx = b.getInt(), cy = b.getInt(), level = b.getInt();
				CollisionField.remove(level, cx, cy);
				MaterialField.remove(level, cx, cy);
			}
			case SharedLink.MSG_TELEPORT -> {
				ByteBuffer b = m.buffer().order(ByteOrder.LITTLE_ENDIAN);
				Session.pendingTeleport = new Session.Teleport(b.getDouble(), b.getDouble(), b.getDouble(), b.getFloat(), b.getFloat());
			}
			case pzcraft.protocol.Wire.MSG_ACTORS -> Session.actors = pzcraft.protocol.Wire.decodeActors(m.payload());
			case pzcraft.protocol.Wire.MSG_VITALS -> Session.vitals = pzcraft.protocol.Wire.decodeVitals(m.payload());
			case pzcraft.protocol.Wire.MSG_TIME -> Session.timeHour = pzcraft.protocol.Wire.decodeTime(m.payload());
			case pzcraft.protocol.Wire.MSG_VEHICLES -> CollisionField.vehicles(pzcraft.protocol.VehicleHull.decode(m.payload()));
			case pzcraft.protocol.Wire.MSG_WEATHER_STATE -> WeatherBridge.pz = pzcraft.protocol.Weather.State.decode(m.payload());			case pzcraft.protocol.Wire.MSG_ZOMBIE_ATTACK -> {
				if (ActorProxies.zombieAttacks.size() < 256) ActorProxies.zombieAttacks.add(pzcraft.protocol.Wire.decodeZombieAttack(m.payload()));
			}
			case pzcraft.protocol.Wire.MSG_PZ_DAMAGE -> {
				if (ActorProxies.pzDamage.size() < 256) ActorProxies.pzDamage.add(pzcraft.protocol.Wire.decodeFloats(m.payload())[0]);
			}
			default -> LOG.warn("unknown message type {}", m.type());
		}
	}

	private static void onTick(Minecraft mc) {
		clientTicks++;
		if (!optionsApplied && mc.options != null) {
			optionsApplied = true;
			// PZ holds the OS focus, so Minecraft must never pause or throttle itself for losing it.
			mc.options.pauseOnLostFocus = false;
			mc.options.onboardAccessibility = false;
			mc.options.enableVsync().set(false);
			mc.options.framerateLimit().set(90); // PZ shares this GPU; the overlay needs ~70 fps at most
			mc.options.renderDistance().set(3);
			mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF); // PZ draws the sky
			// PZ plays its own soundtrack; jukeboxes and note blocks are a separate slider and still sound.
			mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
			mc.options.chatVisibility().set(net.minecraft.world.entity.player.ChatVisiblity.FULL);
			mc.options.gamma().set(0.8); // placed blocks sit in a dark void world; keep them readable
			mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE; // no "Move with WASD" toast over PZ
			// The overlay is drawn at a fixed size over PZ's picture: render Minecraft at 1280x720 whatever the window was.
			mc.getWindow().setWindowed(1280, 720);
			LOG.info("Session options applied (Minecraft music volume {})", mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MUSIC));
		}
		WorldBootstrap.tick(mc);
        BlockSceneExporter.tick(mc);
        PzItems.tickVisuals(mc);
        PzGuns.tick(mc);
		if (clientTicks % 100 == 0 && mc.level != null && PzItemPack.stale() && mc.gui.screen() == null) {
			// PZ exported its items after Minecraft started: rescan the resource packs so the item icons appear.
			LOG.info("PZ item catalogue changed: reloading resource packs");
			mc.getResourcePackRepository().reload();
			mc.reloadResourcePacks();
		}

		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			Session.released = false;
			teleportedAtTick = -1;
			return;
		}
		if (p != lastPlayer) {
			// A fresh Steve (first join or a respawn): hold him until PZ places him.
			lastPlayer = p;
			Session.released = false;
			teleportedAtTick = -1;
			Session.needPlacement = RespawnBridge.target == null;
			if (RespawnBridge.target != null) teleportedAtTick = clientTicks;
			LOG.info("New player entity; asking PZ to place Steve");
		}
		if (p.isDeadOrDying()) {
            // Keep the native death screen until its Respawn button creates the next Steve. PZ's puppet stays alive.
			Session.pendingTeleport=null;Session.needPlacement=false;
			return;
		}
		if (!(p.input instanceof PzInput)) p.input = new PzInput(mc.options);
		if (Session.released && clientTicks % 60 == 0 && mc.hitResult != null) {
			// Also the whole hotbar, so tools (the playtester) can pick a slot by its item without cycling through them.
			StringBuilder hotbar = new StringBuilder();
			for (int i = 0; i < 9; i++) {
				var stack = p.getInventory().getItem(i);
				if (i > 0) hotbar.append(',');
				if (!stack.isEmpty()) hotbar.append(stack); // "57 minecraft:oak_planks", as for the hand above
			}
			// The targeted block: a real Minecraft block, or air when the hit is PZ geometry (see EntityPickMixin).
			String target = mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh
					? String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bh.getBlockPos()).getBlock()))
					: "-";
			LOG.info("crosshair: {} at {} (slot {} = {}) hotbar [{}] target {}", mc.hitResult.getType(), mc.hitResult.getLocation(),
					p.getInventory().getSelectedSlot(), p.getMainHandItem(), hotbar, target);
		}

		IntegratedServer server = mc.getSingleplayerServer();

		Session.Teleport tp = Session.pendingTeleport;
		if (tp != null && server != null) {
			Session.pendingTeleport = null;
			Session.released = false;
			teleportedAtTick = clientTicks;
			hold(p, server, true);
			server.execute(() -> {
				if (server.getPlayerList().getPlayers().isEmpty()) return;
				ServerPlayer sp = server.getPlayerList().getPlayers().getFirst();
				RespawnBridge.initialSpawn(sp, tp);
				sp.connection.teleport(tp.x(), tp.y(), tp.z(), tp.yawDeg(), tp.pitchDeg());
			});
			LOG.info("Teleporting Steve to ({}, {}, {})", tp.x(), tp.y(), tp.z());
		}

		if (!Session.released && clientTicks % 40 == 0) {
			LOG.info("holding Steve: teleported={} sections={} sectionHere={} chunkLoaded={} pos=({}, {}, {}) screen={}",
					teleportedAtTick >= 0, CollisionField.sectionCount(), CollisionField.hasSectionAt(p.getX(), p.getZ()),
					mc.level.hasChunkAt(p.blockPosition()), p.getX(), p.getY(), p.getZ(),
					mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName());
		}
		if (!Session.released) {
			// Until PZ has placed Steve and its collision under him has arrived, nothing may let him fall into the void.
			hold(p, server, true);
			if (teleportedAtTick >= 0 && clientTicks - teleportedAtTick > 5
					&& (RespawnBridge.target == null || RespawnBridge.acknowledged == RespawnBridge.target.sequence())
					&& TerrainCoverage.known(p.getX(), p.getZ())
					&& mc.level.hasChunkAt(p.blockPosition())) {
				Session.released = true;
				hold(p, server, false);
				Session.readyToAnnounce = true;
				RespawnBridge.target = null;
				LOG.info("Steve released at ({}, {}, {}) with {} collision sections", p.getX(), p.getY(), p.getZ(), CollisionField.sectionCount());
			}
		}
	}

	private static void hold(LocalPlayer p, IntegratedServer server, boolean hold) {
		p.setNoGravity(hold);
		if (hold) p.setDeltaMovement(0, 0, 0);
		if (server != null) {
			server.execute(() -> {
				if (server.getPlayerList().getPlayers().isEmpty()) return;
				server.getPlayerList().getPlayers().getFirst().setNoGravity(hold);
			});
		}
	}
}


