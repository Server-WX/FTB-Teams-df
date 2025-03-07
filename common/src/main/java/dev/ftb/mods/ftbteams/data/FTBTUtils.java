package dev.ftb.mods.ftbteams.data;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.util.UUIDTypeAdapter;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.math.MathUtils;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class FTBTUtils {
	public static final GameProfile NO_PROFILE = new GameProfile(new UUID(0L, 0L), "-");

	@Nullable
	public static ServerPlayer getPlayerByUUID(MinecraftServer server, @Nullable UUID id) {
		return id == null || id == Util.NIL_UUID ? null : server.getPlayerList().getPlayer(id);
	}

	public static GameProfile normalize(@Nullable GameProfile profile) {
		if (profile == null || profile.getId() == null || profile.getName() == null || profile.equals(NO_PROFILE)) {
			return NO_PROFILE;
		}

		if (!profile.getProperties().isEmpty()) {
			return new GameProfile(profile.getId(), profile.getName());
		}

		return profile;
	}

	public static String serializeProfile(@Nullable GameProfile profile) {
		if (normalize(profile) == NO_PROFILE) {
			return "";
		}

		return UUIDTypeAdapter.fromUUID(profile.getId()) + ":" + profile.getName();
	}

	public static GameProfile deserializeProfile(String string) {
		if (string.isEmpty()) {
			return NO_PROFILE;
		}

		try {
			String[] s = string.split(":", 2);
			UUID uuid = UUIDTypeAdapter.fromString(s[0]);
			String name = s[1];
			return normalize(new GameProfile(uuid, name));
		} catch (Exception ex) {
			return NO_PROFILE;
		}
	}

	public static Color4I randomColor() {
		return Color4I.hsb(MathUtils.RAND.nextFloat(), 0.65F, 1F);
	}

	public static boolean canPlayerUseCommand(ServerPlayer player, String command) {
		List<String> parts = Arrays.asList(command.split("\\."));
		CommandNode<CommandSourceStack> node = player.getServer().getCommands().getDispatcher().findNode(parts);
		return node != null && node.canUse(player.createCommandSourceStack());
	}
}
