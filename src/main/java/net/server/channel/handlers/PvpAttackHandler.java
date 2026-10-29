package net.server.channel.handlers;

import client.BuffStat;
import client.Character;
import client.Client;
import client.SkillFactory;
import constants.game.GameConstants;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.server.world.Party;
import constants.skills.Assassin;
import constants.skills.ChiefBandit;
import constants.skills.Hermit;
import constants.skills.Hunter;
import constants.skills.Marauder;
import constants.skills.Marksman;
import constants.skills.NightWalker;
import constants.skills.Sniper;
import constants.skills.ThunderBreaker;
import server.StatEffect;
import server.skills.BranchSkills;
import server.skills.ComboAttack;
import server.skills.PvpRules;
import server.skills.Tweaks;
import tools.Randomizer;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.life.MobSkillType;
import server.maps.MapleMap;
import tools.PacketCreator;

/**
 * A swing in a high-level hunting stage that also hit players.
 * The monster attack arrives on its own packet and is unchanged.
 */
public final class PvpAttackHandler extends AbstractPacketHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PvpAttackHandler.class);

    // --- Apply player hits from a stage swing (one line)
    // Related up:   OpenStory Combat::hit_players, opcode PVP_ATTACK 0x16D
    // Related down: Character.defeatInPvp, PacketCreator.damagePlayer
    // Shared state: the stage map, both players' parties, target HP
    // Order:        reject first (wrong map, party, range), then guards, then HP. A killing hit leaves them on the grave.
    // Pitfall:      the bounced hit uses addHP. Running it through DamageGuards again would reflect forever.
    // Safety:       damage is capped from the attacker's own stats so a edited client cannot send a huge hit
    @Override
    public void handlePacket(InPacket p, Client c) {
        Character chr = c.getPlayer();
        if (chr == null || !chr.isAlive()) {
            return;
        }
        MapleMap map = chr.getMap();
        if (map == null || !map.isPvpStage()) {
            log.info("pvp hit from {}: refused (map {} is not a PvP map)", chr.getName(), map == null ? -1 : map.getId());
            return;
        }
        int count = p.readByte();
        if (count < 1 || count > 14) {
            log.info("pvp hit from {}: refused (bad target count {})", chr.getName(), count);
            return;
        }
        int skillId = p.readInt();
        int lines = 1;
        if (p.available() >= 1) {
            lines = p.readByte();
        }
        if (lines < 1) {
            lines = 1;
        }
        if (lines > 15) {
            lines = 15;
        }
        // StoryFighter: never more lines than the skill has (Twin Slash 2, the plain attack 1)
        if (server.skills.StoryDamage.applies(skillId)) {
            lines = Math.min(lines, server.skills.StoryDamage.maxLines(chr, skillId));
        }
        if (server.skills.CustomSkills.blockPacedHit(chr, skillId)) {
            log.info("pvp hit from {} skill {}: refused (paced skill already hit)", chr.getName(), skillId);
            return;
        }
        // StoryFighter: a skill with a cooldown (Chain, Sniper Shot) hits players once per use. Its monster packet
        // comes first and starts the cooldown, so the skill must be cooling, and this use not spent yet.
        if (!server.skills.StoryCooldowns.acceptPvp(chr, skillId)) {
            log.info("pvp hit from {} skill {}: refused (cooldown: not cooling or this use already hit)", chr.getName(), skillId);
            return;
        }
        // One swing, however many people it touches. Faster than this is the old Genesis spam.
        if (!chr.acceptPvpSwing(skillId)) {
            log.info("pvp hit from {} skill {}: refused (swing too fast)", chr.getName(), skillId);
            return;
        }
        boolean magic = server.skills.CustomSkills.magicHit(skillId);
        // The shadow repeats every line, as on monsters. The meso skills already carry their whole hit.
        boolean partner = PvpRules.SHADOW_PARTNER && !magic && lines * 2 <= 15
                && skillId != Hermit.SHADOW_MESO && skillId != ChiefBandit.MESO_EXPLOSION
                && chr.getBuffEffect(BuffStat.SHADOWPARTNER) != null;
        int partnerPercent = partner ? shadowPercent(chr, skillId) : 0;
        int dealt = 0;
        boolean anyLanded = false;
        java.util.Set<Integer> directTargets = new java.util.HashSet<>();
        server.skills.StoryAttacks.beginPvp();
        // StoryFighter: never more players than the skill's targets (Chain and Sniper Shot: 1)
        int maxTargets = server.skills.StorySkills.isStory(skillId)
                ? Math.max(1, server.skills.StorySkills.value(skillId, chr.getSkillLevel(skillId), "mobCount")) : 14;
        int taken = 0;
        // One log line per packet: who sent it and why each target was skipped (testclient/server.log)
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int cid = p.readInt();
            int damage = p.readInt();
            if (cid == chr.getId() || damage < 1) {
                why.append(' ').append(cid).append("=self/no damage");
                continue;
            }
            Character target = map.getCharacterById(cid);
            if (target == null || target.getMap() != map || !target.isAlive()) {
                why.append(' ').append(cid).append("=not here/dead");
                continue;
            }
            if (server.starter.StoryParty.allies(chr, target)) {   // same party or same team
                why.append(' ').append(target.getName()).append("=same party or team");
                continue;
            }
            if (target.getBuffedValue(BuffStat.DARKSIGHT) != null) {
                why.append(' ').append(target.getName()).append("=hidden");
                continue;
            }
            if (distanceTooFar(chr, target)) {
                why.append(' ').append(target.getName()).append("=too far (800 px)");
                continue;
            }
            // Chain reaches any direction (user 2026-10-28): the body within reach of the caster's centre, no Dome wall
            // on the line (anti-cheat #22 note); every other skill keeps the facing box below
            if (server.skills.StoryAttacks.isChain(skillId)) {
                String refused = server.skills.StoryAttacks.chainRefusal(chr, skillId, target.getPosition(),
                        server.skills.StoryAttacks.HERO_BODY, server.skills.StorySkills.hitboxLagPvpPx());
                if (refused != null) {
                    why.append(' ').append(target.getName()).append("=chain ").append(refused);
                    continue;
                }
            } else if (!server.skills.StorySkills.inReach(chr, skillId, target.getPosition())) {
                why.append(' ').append(target.getName()).append("=out of reach");
                continue;
            }
            // the client tests the other hero's feet point against the box (no body), both sides; room hitbox_lag_pvp_px
            if (!server.skills.StoryAttacks.isChain(skillId) && !server.skills.StorySkills.inBox(chr, skillId, target.getPosition())) {
                why.append(' ').append(target.getName()).append("=out of box (dx ")
                        .append(target.getPosition().x - chr.getPosition().x).append(" dy ")
                        .append(target.getPosition().y - chr.getPosition().y).append(", gap ")
                        .append(server.skills.StorySkills.boxGap(chr, skillId, target.getPosition(), null, 0))
                        .append(" px, room ").append(server.skills.StorySkills.hitboxLagPvpPx()).append(')');
                continue;
            }
            if (taken >= maxTargets) {
                why.append(' ').append(target.getName()).append("=over target count");
                continue;
            }
            why.append(' ').append(target.getName()).append("=hit");
            taken++;
            int pve = damage;
            // StoryFighter: the same cap as on monsters (our formula; the victim's defense comes in PvpHits)
            if (server.skills.StoryDamage.applies(skillId)) {
                pve = (int) Math.min(pve, server.skills.StoryDamage.maxLine(chr, skillId, null));
            }
            chr.setElementLine(pve);
            boolean landed = false;
            for (int n = 0; n < lines && target.isAlive() && chr.isAlive(); n++) {
                // StoryFighter: every line rolls its own crit (server.starter.StoryStats); old skills never crit here
                dealt += server.skills.PvpHits.apply(chr, target, pve, magic, server.skills.StoryDamage.applies(skillId));
                landed = true;
            }
            if (partnerPercent > 0) {
                int shadow = Math.max(1, (int) ((long) pve * partnerPercent / 100));
                for (int n = 0; n < lines && target.isAlive() && chr.isAlive(); n++) {
                    dealt += server.skills.PvpHits.apply(chr, target, shadow, magic);
                }
            }
            if (landed) {
                anyLanded = true;
                directTargets.add(target.getId());
                server.skills.StoryAttacks.onPlayerHit(chr, target, skillId, pve);
                server.skills.PvpDots.afterSwing(chr, target, skillId, lines);
                server.skills.ElementEffects.onHunter(chr, target, skillId, pve);
                server.skills.ElementEffects.finalAttackHunter(chr, target, pve);
                server.skills.ElementEffects.hamstringHunter(chr, target);
                freezeHunter(chr, target, skillId);
                stunHunter(chr, target, skillId);
                server.skills.PvpStatus.afterSwing(chr, target, skillId);
                dealt += mortalBlow(chr, target, pve);
            }
        }
        log.info("pvp hit from {} skill {} lines {}:{}", chr.getName(), skillId, lines, why);
        server.skills.StoryAttacks.afterPvp(chr, skillId, directTargets);
        if (anyLanded) {
            drainHeal(chr, skillId, dealt);
            if (PvpRules.COMBO_ORBS && skillId != 1111008 && !GameConstants.isFinisherSkill(skillId)) {
                ComboAttack.gainOrb(chr);
            }
        }
    }

    // --- Shadow Partner's share of a line, the same as on monsters (one line)
    // Related down: AbstractDealDamageHandler shadow lines, Tweaks.SHADOW_PARTNER_PERCENT
    private static int shadowPercent(Character chr, int skillId) {
        StatEffect partner = chr.getBuffEffect(BuffStat.SHADOWPARTNER);
        if (partner == null) {
            return 0;
        }
        int percent = Tweaks.SHADOW_PARTNER_PERCENT ? (skillId == 0 ? partner.getX() : partner.getY()) : 50;
        return Math.max(0, percent);
    }

    // --- Drain heals from the HP hunters lost (one line)
    // Related down: Character.addHP. On monsters AbstractDealDamageHandler heals the same x percent.
    // Pitfall:      at most half of max HP per swing, as on monsters.
    private static void drainHeal(Character chr, int skillId, int dealt) {
        if (!PvpRules.DRAIN_HEALS || dealt < 1) {
            return;
        }
        if (skillId != Assassin.DRAIN && skillId != Marauder.ENERGY_DRAIN && skillId != ThunderBreaker.ENERGY_DRAIN && skillId != NightWalker.VAMPIRE) {
            return;
        }
        int level = chr.getSkillLevel(skillId);
        if (level < 1) {
            return;
        }
        StatEffect effect = SkillFactory.getSkill(skillId).getEffect(level);
        int heal = (int) Math.min((long) dealt * effect.getX() / 100, chr.getCurrentMaxHp() / 2);
        if (heal > 0) {
            chr.addHP(heal);
        }
    }

    // --- Mortal Blow on a hunter: a bonus line instead of the instant kill (one line)
    // Related down: BranchSkills.mortalBlow, PvpHits.apply. Same x (HP percent) and y (chance) as on monsters.
    // Pitfall:      an instant kill on a player is no fun, so PvpRules.MORTAL_BLOW_BONUS_PERCENT of the line lands instead.
    private static int mortalBlow(Character chr, Character target, int pve) {
        if (!PvpRules.MORTAL_BLOW || !target.isAlive() || target.pvpShielded()) {
            return 0;
        }
        int id = BranchSkills.mortalBlow(chr);
        if (id == 0) {
            return 0;
        }
        StatEffect effect = SkillFactory.getSkill(id).getEffect(chr.getSkillLevel(id));
        if ((long) target.getHp() * 100 > (long) target.getCurrentMaxHp() * effect.getX()) {
            return 0;
        }
        if (Randomizer.rand(1, 100) > effect.getY()) {
            return 0;
        }
        int bonus = Math.max(1, (int) ((long) pve * PvpRules.MORTAL_BLOW_BONUS_PERCENT / 100));
        return server.skills.PvpHits.apply(chr, target, bonus, false);
    }


    // A stage is large. This only stops a hit reported from across the map.
    private static boolean distanceTooFar(Character a, Character b) {
        int dx = a.getPosition().x - b.getPosition().x;
        int dy = a.getPosition().y - b.getPosition().y;
        return (long) dx * dx + (long) dy * dy > 800L * 800L;
    }

    // --- Archer Blizzard locks a hunter the way it freezes a monster (one line)
    // Related up:   handlePacket, after the hit lands
    // Related down: Character.giveDebuff. The 7 is the word freeze on the client.
    // Pitfall:      the skill's time is in seconds. A shield already stopped the hit, so it stops the freeze too.
    //               giveDebuff still caps a stage lock at 2 seconds.
    private static void freezeHunter(Character attacker, Character victim, int skillId) {
        if (!PvpRules.ARCHER_STUNS || skillId != Sniper.BLIZZARD || victim.pvpShielded() || !victim.isAlive()) {
            return;
        }
        MobSkill stun = MobSkillFactory.getMobSkill(MobSkillType.STUN, 1).orElse(null);
        if (stun == null) {
            return;
        }
        client.Skill skill = client.SkillFactory.getSkill(skillId);
        long ms = server.skills.CustomSkills.MAX_LOCK_MS;
        if (skill != null) {
            StatEffect effect = skill.getEffect(Math.max(1, attacker.getSkillLevel(skill)));
            if (effect != null && effect.getDuration() > 0) {
                ms = effect.getDuration();
                if (ms < 1000L) {
                    ms *= 1000L;
                }
            }
        }
        victim.giveDebuff(client.Disease.STUN, stun, ms, 7);
    }

    // --- Snipe and Arrow Bomb stun every hunter they hit (one line)
    // Related up:   handlePacket, after the hit lands
    // Related down: Character.giveDebuff. A stage stun still stops at 2 seconds.
    // Pitfall:      a shield already stopped the hit. Do not stun through it.
    private static void stunHunter(Character attacker, Character victim, int skillId) {
        if (!PvpRules.ARCHER_STUNS || (skillId != Hunter.ARROW_BOMB && skillId != Marksman.SNIPE) ||victim.pvpShielded() || !victim.isAlive()) {
            return;
        }
        MobSkill stun = MobSkillFactory.getMobSkill(MobSkillType.STUN, 1).orElse(null);
        if (stun == null) {
            return;
        }
        client.Skill skill = client.SkillFactory.getSkill(skillId);
        long ms = server.skills.CustomSkills.MAX_LOCK_MS;
        if (skill != null) {
            StatEffect effect = skill.getEffect(Math.max(1, attacker.getSkillLevel(skill)));
            if (effect != null && effect.getDuration() > 0) {
                ms = effect.getDuration();
                if (ms < 300L) {
                    ms *= 1000L;
                }
            }
        }
        victim.giveDebuff(client.Disease.STUN, stun, ms);
    }
}
