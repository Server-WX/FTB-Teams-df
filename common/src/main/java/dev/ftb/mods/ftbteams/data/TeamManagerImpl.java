package dev.ftb.mods.ftbteams.data;

import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbteams.FTBTeams;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.api.event.PlayerLoggedInAfterTeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamManagerEvent;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import dev.ftb.mods.ftbteams.net.SyncMessageHistoryMessage;
import dev.ftb.mods.ftbteams.net.SyncTeamsMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * @author LatvianModder
 */
public class TeamManagerImpl implements TeamManager {
	public static final LevelResource FOLDER_NAME = new LevelResource("ftbteams");

	public static TeamManagerImpl INSTANCE;

	private final MinecraftServer server;
	private UUID id;
	private boolean shouldSave;
	private final Map<UUID, PlayerTeam> knownPlayers;
	private final Map<UUID, AbstractTeam> teamMap;
	Map<String, Team> nameMap;
	private CompoundTag extraData;

	public TeamManagerImpl(MinecraftServer s) {
		server = s;
		knownPlayers = new LinkedHashMap<>();
		teamMap = new LinkedHashMap<>();
		extraData = new CompoundTag();
	}

	@Override
	public MinecraftServer getServer() {
		return server;
	}

	@Override
	public UUID getId() {
		if (id == null) {
			id = UUID.randomUUID();
		}

		return id;
	}

	@Override
	public Map<UUID, ? extends Team> getKnownPlayerTeams() {
		return Collections.unmodifiableMap(knownPlayers);
	}

	public Map<UUID, AbstractTeam> getTeamMap() {
		return teamMap;
	}

	@Override
	public Collection<Team> getTeams() {
		//noinspection UnstableApiUsage
		ImmutableList.Builder<Team> b = ImmutableList.builderWithExpectedSize(getTeamMap().size());
		teamMap.values().forEach(b::add);
		return b.build();
	}

	public Map<String, Team> getTeamNameMap() {
		if (nameMap == null) {
			nameMap = new HashMap<>();
			for (AbstractTeam team : teamMap.values()) {
				nameMap.put(team.getShortName(), team);
			}
		}

		return nameMap;
	}

	@Override
	public Optional<Team> getTeamByID(UUID teamId) {
		return Optional.of(teamMap.get(teamId));
	}

	@Override
	public Optional<Team> getTeamByName(String name) {
		return Optional.ofNullable(getTeamNameMap().get(name));
	}

	@Override
	public Optional<Team> getPlayerTeamForPlayerID(UUID uuid) {
		return Optional.ofNullable(getPersonalTeamForPlayerID(uuid));
	}

	public PlayerTeam getPersonalTeamForPlayerID(UUID uuid) {
		return knownPlayers.get(uuid);
	}

	@Override
	public Optional<Team> getTeamForPlayerID(UUID uuid) {
		PlayerTeam t = knownPlayers.get(uuid);
		return t == null ? Optional.empty() : Optional.ofNullable(t.getEffectiveTeam());
	}

	@Override
	public Optional<Team> getTeamForPlayer(ServerPlayer player) {
		return getTeamForPlayerID(player.getUUID());
	}

	@Override
	public boolean arePlayersInSameTeam(UUID id1, UUID id2) {
		return getTeamForPlayerID(id1).map(team1 -> getTeamForPlayerID(id2)
				.map(team2 -> team1.getId().equals(team2.getId())).orElse(false))
				.orElse(false);
	}

	public void load() {
		id = null;
		Path directory = server.getWorldPath(FOLDER_NAME);

		if (Files.notExists(directory) || !Files.isDirectory(directory)) {
			return;
		}

		CompoundTag dataFileTag = SNBT.read(directory.resolve("ftbteams.snbt"));

		if (dataFileTag != null) {
			if (dataFileTag.contains("id")) {
				id = UUID.fromString(dataFileTag.getString("id"));
			}

			extraData = dataFileTag.getCompound("extra");
			TeamManagerEvent.LOADED.invoker().accept(new TeamManagerEvent(this));
		}

		for (TeamType type : TeamType.values()) {
			Path dir = directory.resolve(type.getSerializedName());

			if (Files.exists(dir) && Files.isDirectory(dir)) {
				try (Stream<Path> s = Files.list(dir)) {
					s.filter(path -> path.getFileName().toString().endsWith(".snbt")).forEach(file -> {
						CompoundTag nbt = SNBT.read(file);
						if (nbt != null) {
							AbstractTeam team = type.createTeam(this, UUID.fromString(nbt.getString("id")));
							teamMap.put(team.id, team);
							team.deserializeNBT(nbt);
						}
					});
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}

		for (AbstractTeam team : teamMap.values()) {
			if (team instanceof PlayerTeam) {
				knownPlayers.put(team.id, (PlayerTeam) team);
			}
		}

		for (AbstractTeam team : teamMap.values()) {
			if (team instanceof PartyTeam) {
				for (UUID member : team.getMembers()) {
					PlayerTeam t = knownPlayers.get(member);
					if (t != null) {
						t.setEffectiveTeam(team);
					}
				}
			}
		}

		FTBTeams.LOGGER.info("loaded team data: {} known players, {} teams total", knownPlayers.size(), teamMap.size());
	}

	public void markDirty() {
		shouldSave = true;
		nameMap = null;  // in case any team has changed their stringID (i.e. "friendly" name)
	}

	public void saveNow() {
		Path directory = server.getWorldPath(FOLDER_NAME);

		if (Files.notExists(directory)) {
			try {
				Files.createDirectories(directory);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		if (shouldSave) {
			TeamManagerEvent.SAVED.invoker().accept(new TeamManagerEvent(this));
			SNBT.write(directory.resolve("ftbteams.snbt"), serializeNBT());
			shouldSave = false;
		}

		for (TeamType type : TeamType.values()) {
			Path path = directory.resolve(type.getSerializedName());

			if (Files.notExists(path)) {
				try {
					Files.createDirectories(path);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}

		for (AbstractTeam team : teamMap.values()) {
			team.saveIfNeeded(directory);
		}
	}

	public SNBTCompoundTag serializeNBT() {
		SNBTCompoundTag nbt = new SNBTCompoundTag();
		nbt.putString("id", getId().toString());
		nbt.put("extra", extraData);
		return nbt;
	}

	private ServerTeam createServerTeam(ServerPlayer player, String name) {
		ServerTeam team = new ServerTeam(this, UUID.randomUUID());
		teamMap.put(team.id, team);

		team.setProperty(TeamProperties.DISPLAY_NAME, name.isEmpty() ? team.id.toString().substring(0, 8) : name);
		team.setProperty(TeamProperties.COLOR, FTBTUtils.randomColor());

		team.onCreated(player);
		return team;
	}

	private PartyTeam createPartyTeam(ServerPlayer player, String name) {
		PartyTeam team = new PartyTeam(this, UUID.randomUUID());
		team.owner = player.getUUID();
		teamMap.put(team.id, team);

		team.setProperty(TeamProperties.DISPLAY_NAME, name.isEmpty() ? (player.getGameProfile().getName() + "'s Party") : name);
		team.setProperty(TeamProperties.COLOR, FTBTUtils.randomColor());

		team.onCreated(player);
		return team;
	}

	private PlayerTeam createPlayerTeam(@Nullable ServerPlayer player, UUID playerId, String playerName) {
		PlayerTeam team = new PlayerTeam(this, playerId);

		team.setPlayerName(playerName);

		team.setProperty(TeamProperties.DISPLAY_NAME, playerName);
		team.setProperty(TeamProperties.COLOR, FTBTUtils.randomColor());

		team.addMember(playerId, TeamRank.OWNER);

		return team;
	}

	public void playerLoggedIn(@Nullable ServerPlayer player, UUID id, String name) {
		PlayerTeam team = knownPlayers.get(id);
		boolean syncToAll = false;

		FTBTeams.LOGGER.debug("player {} logged in, player team = {}", id, team);

		if (team == null) {
			FTBTeams.LOGGER.debug("creating new player team for player {}", id);

			team = createPlayerTeam(player, id, name);
			teamMap.put(id, team);
			knownPlayers.put(id, team);

			team.onCreated(player);

			syncToAll = true;
			team.onPlayerChangeTeam(null, id, player, false);

			FTBTeams.LOGGER.debug("  - team created");
		} else if (!team.getPlayerName().equals(name)) {
			FTBTeams.LOGGER.debug("updating player name: {} -> {}", team.getPlayerName(), name);
			team.setPlayerName(name);
			team.markDirty();
			markDirty();
			syncToAll = true;
		}

		FTBTeams.LOGGER.debug("syncing player team data, all = {}", syncToAll);
		if (player != null) {
			syncAllToPlayer(player, team.getEffectiveTeam());
		}
		if (syncToAll) {
			syncToAll(team.getEffectiveTeam());
		}

		FTBTeams.LOGGER.debug("updating team presence");
		team.setOnline(true);
		team.updatePresence();

		if (player != null) {
			FTBTeams.LOGGER.debug("sending team login event for {}...", player.getUUID());
			TeamEvent.PLAYER_LOGGED_IN.invoker().accept(new PlayerLoggedInAfterTeamEvent(team.getEffectiveTeam(), player));
			FTBTeams.LOGGER.debug("team login event for {} sent", player.getUUID());
		}
	}

	public void playerLoggedOut(ServerPlayer player) {
		PlayerTeam team = knownPlayers.get(player.getUUID());

		if (team != null) {
			team.setOnline(false);
			team.updatePresence();
		}
	}

	/**
	 * Sync team information about all teams to one player, along with that player's team's message history.
	 * Called on player login.
	 *
	 * @param player player to sync to
	 * @param selfTeam the player's own team, which could be a party team
	 */
	public void syncAllToPlayer(ServerPlayer player, AbstractTeam selfTeam) {
		ClientTeamManagerImpl manager = ClientTeamManagerImpl.forSyncing(this, teamMap.values());

		new SyncTeamsMessage(manager, selfTeam, true).sendTo(player);
		new SyncMessageHistoryMessage(selfTeam).sendTo(player);
		server.getPlayerList().sendPlayerPermissionLevel(player);
	}

	/**
	 * Sync only the given team(s) to all players. Called when one or more teams are modified in any way. In practice,
	 * this will always be one or two teams (two when a player is joining or leaving a team).
	 *
	 * @param teams the teams to sync, which may have been deleted already
	 */
	public void syncToAll(Team... teams) {
		if (teams.length == 0) return;

		ClientTeamManagerImpl manager = ClientTeamManagerImpl.forSyncing(this, Arrays.stream(teams).toList());

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			getTeamForPlayer(player).ifPresent(selfTeam -> {
				new SyncTeamsMessage(manager, selfTeam, false).sendTo(player);
				if (teams.length > 1) {
					new SyncMessageHistoryMessage(selfTeam).sendTo(player);
				}
			});
		}
	}

	// Command Handlers //

	public Pair<Integer, PartyTeam> createParty(ServerPlayer player, String name) throws CommandSyntaxException {
		return createParty(player, name, null, null);
	}

	public Pair<Integer, PartyTeam> createParty(ServerPlayer player, String name, @Nullable String description, @Nullable Color4I color) throws CommandSyntaxException {
		if (FTBTeamsAPI.api().getCustomPartyCreationHandler() != null) {
			throw TeamArgument.API_OVERRIDE.create();
		}

		if (!FTBTUtils.canPlayerUseCommand(player, "ftbteams.party.create")) {
			throw TeamArgument.NO_PERMISSION.create();
		}

		UUID id = player.getUUID();
		Team oldTeam = getTeamForPlayer(player).orElseThrow(() -> TeamArgument.TEAM_NOT_FOUND.create(player.getUUID()));

		if (!(oldTeam instanceof PlayerTeam playerTeam)) {
			throw TeamArgument.ALREADY_IN_PARTY.create();
		}

		PartyTeam team = createPartyTeam(player, name);
		if (description != null) team.setProperty(TeamProperties.DESCRIPTION, description);
		if (color != null) team.setProperty(TeamProperties.COLOR, color);

		playerTeam.setEffectiveTeam(team);

		team.addMember(id, TeamRank.OWNER);
		team.sendMessage(Util.NIL_UUID, Component.translatable("ftbteams.message.joined", player.getName()).withStyle(ChatFormatting.YELLOW));
		team.markDirty();

		playerTeam.removeMember(id);
		playerTeam.markDirty();

		playerTeam.updatePresence();
		syncToAll(team, playerTeam);
		team.onPlayerChangeTeam(playerTeam, id, player, false);
		return Pair.of(Command.SINGLE_SUCCESS, team);
	}

	public Pair<Integer, ServerTeam> createServer(CommandSourceStack source, String name) throws CommandSyntaxException {
		if (name.length() < 3) {
			throw TeamArgument.NAME_TOO_SHORT.create();
		}
		ServerTeam team = createServerTeam(source.getPlayerOrException(), name);
		source.sendSuccess(() -> Component.translatable("ftbteams.message.created_server_team", team.getName()), true);
		syncToAll(team);
		return Pair.of(Command.SINGLE_SUCCESS, team);
	}

	public Component getPlayerName(@Nullable UUID id) {
		if (id == null || id.equals(Util.NIL_UUID)) {
			return Component.literal("System").withStyle(ChatFormatting.LIGHT_PURPLE);
		}

		PlayerTeam team = knownPlayers.get(id);
		return Component.literal(team == null ? "Unknown" : team.getPlayerName()).withStyle(ChatFormatting.YELLOW);
	}

	@Override
	public CompoundTag getExtraData() {
		return extraData;
	}

	void deleteTeam(Team team) {
		teamMap.remove(team.getId());
		markDirty();
	}

	void tryDeleteTeamFile(String teamFileName, String subfolderName) {
		Path deletedPath = getServer().getWorldPath(FOLDER_NAME).resolve("deleted");
		Path teamFilePath = getServer().getWorldPath(FOLDER_NAME).resolve(subfolderName).resolve(teamFileName);
		try {
			if (Files.notExists(deletedPath)) {
				Files.createDirectories(deletedPath);
			}
			Files.move(teamFilePath, deletedPath.resolve(teamFileName));
		} catch (IOException e) {
			e.printStackTrace();
			try {
				Files.deleteIfExists(teamFilePath);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
	}
}
