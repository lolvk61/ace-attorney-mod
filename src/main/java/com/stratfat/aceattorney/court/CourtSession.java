package com.stratfat.aceattorney.court;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CourtSession {
	/** One witness statement in a testimony. */
	public record Statement(String speaker, String text) {
	}

	private final UUID judge;
	private final Map<UUID, CourtRole> roles = new LinkedHashMap<>();
	private final List<Evidence> evidence = new ArrayList<>();
	private final List<Statement> testimony = new ArrayList<>();

	public CourtSession(UUID judge) {
		this.judge = judge;
		roles.put(judge, CourtRole.JUDGE);
	}

	public UUID judge() {
		return judge;
	}

	public Map<UUID, CourtRole> roles() {
		return roles;
	}

	public List<Evidence> evidence() {
		return evidence;
	}

	public List<Statement> testimony() {
		return testimony;
	}

	public boolean isJudge(UUID player) {
		return roles.get(player) == CourtRole.JUDGE;
	}

	public boolean isParticipant(UUID player) {
		return roles.containsKey(player);
	}

	public void setRole(UUID player, CourtRole role) {
		roles.put(player, role);
	}
}
