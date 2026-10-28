/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package client;

import server.skills.CustomSkills;
import server.StatEffect;
import server.life.Element;

import java.util.ArrayList;
import java.util.List;

public class Skill {
    private final int id;
    private final List<StatEffect> effects = new ArrayList<>();
    private Element element;
    private int animationTime;
    private final int job;
    private boolean action;

    public Skill(int id) {
        this.id = id;
        this.job = id / 10000;
    }

    public int getId() {
        return id;
    }

    // --- Skill levels past the data, up to 999 (one line)
    // Related up:   SkillFactory.loadFromData (setGrowth), Character skill levels, AssignSPProcessor
    // Related down: StatEffect.loadSkillEffectFromData reads a generated level like a real one
    // Order:        each level past the data adds 1 to the effect (a 240% hit becomes 241%).
    //               A negative effect moves by 1 the other way. MP and HP cost stay.
    //               Counts, range, hit box, mastery and cooldown stay. Chance stops at 100.
    //               Drop rate, EXP rate and meso rate stay at the last data level.
    //               Meso Explosion's mastery (x) adds 10 per level past the data, which is 1% damage.
    // Pitfall:      OpenStory SkillData.grow uses this same rule. Change both together,
    //               or the client's damage and the server's damage check disagree.
    public static final int MAX_SKILL_LEVEL = 999;

    private static final java.util.Set<String> FIXED = java.util.Set.of(
            "mobCount", "attackCount", "bulletCount", "bulletConsume", "itemCon", "itemConNo", "itemConsume",
            "morph", "range", "mastery", "cooltime", "hs", "action");

    private record Leaf(String name, provider.wz.DataType type, Object value) {}

    private List<Leaf> lastLevel = List.of();
    private java.util.Map<String, Integer> previousLevel = java.util.Map.of();
    private boolean buff;
    private final java.util.Map<Integer, StatEffect> generated = new java.util.concurrent.ConcurrentHashMap<>();

    /** The last two levels in the data (previous may be null for a one-level skill). */
    void setGrowth(provider.Data previous, provider.Data last, boolean isBuff) {
        this.buff = isBuff;
        List<Leaf> leaves = new ArrayList<>();
        if (last != null) {
            for (provider.Data child : last.getChildren()) {
                if (child.getChildren().isEmpty()) {
                    leaves.add(new Leaf(child.getName(), child.getType(), child.getData()));
                }
            }
        }
        java.util.Map<String, Integer> prev = new java.util.HashMap<>();
        if (previous != null) {
            for (provider.Data child : previous.getChildren()) {
                Integer n = number(child.getData());
                if (n != null) {
                    prev.put(child.getName(), n);
                }
            }
        }
        this.lastLevel = leaves;
        this.previousLevel = prev;
    }

    private static Integer number(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Short s) {
            return (int) s;
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** A number at a level past the data: same rule as OpenStory SkillData.grow. */
    public static int grow(int skillId, String name, int last, Integer previous, int levelsPast) {
        // Drop rate, EXP rate and meso rate stay on the last data level. So does Meso Up, which is one of them.
        if (CustomSkills.stopsAtDataLevel(skillId) || FIXED.contains(name) || previous == null || levelsPast <= 0 || last == 0) {
            return cap(skillId, name, last);
        }
        // Meso Explosion: the book calls x "Mastery" and shows x / 10. 1000 is 100%.
        // Each level past 30 adds 10, so the line goes 101%, 102% and the hit is 1% stronger.
        // Pitfall: attackCount stays 20. That is how many icons it can explode. Do not grow that here.
        // Pitfall: OpenStory SkillData.grow adds the same 10. The hit itself is MesoExplosion.line.
        if (skillId == 4211006 && "x".equals(name)) {
            return last + 10 * levelsPast;
        }
        // A higher level must not cost more MP or HP.
        if ("mpCon".equals(name) || "hpCon".equals(name)) {
            return last;
        }
        long value = (long) last + (last < 0 ? -levelsPast : levelsPast);
        if ("prop".equals(name)) {
            value = Math.min(100, value);
        }
        if (last >= 0) {
            value = Math.max(0, value);
        } else {
            value = Math.min(0, value);
        }
        value = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
        return cap(skillId, name, (int) value);
    }

    private static int cap(int skillId, String name, int value) {
        // Holy Symbol: the EXP bonus never goes above 30%.
        if (skillId == 2311003 && "x".equals(name)) {
            return Math.min(30, value);
        }
        if ("prop".equals(name)) {
            return Math.min(100, Math.max(0, value));
        }
        return value;
    }

    private StatEffect generate(int level) {
        int past = level - effects.size();
        provider.GeneratedData node = provider.GeneratedData.folder(Integer.toString(level));
        for (Leaf leaf : lastLevel) {
            Integer n = number(leaf.value());
            if (n != null) {
                int value = grow(id, leaf.name(), n, previousLevel.get(leaf.name()), past);
                node.add(new provider.GeneratedData(leaf.name(), provider.wz.DataType.INT, value));
            } else {
                node.add(new provider.GeneratedData(leaf.name(), leaf.type(), leaf.value()));
            }
        }
        return StatEffect.loadSkillEffectFromData(node, id, buff);
    }

    public StatEffect getEffect(int level) {
        if (level <= effects.size()) {
            return effects.get(level - 1);
        }
        if (effects.isEmpty()) {
            return null;
        }
        return generated.computeIfAbsent(Math.min(level, MAX_SKILL_LEVEL), this::generate);
    }

    /** Original Maple last data level, unless CustomSkills sets a cap of our own. */
    public int getMaxLevel() {
        int cap = CustomSkills.maxLevel(id);
        if (cap > 0) {
            return cap;
        }
        return effects.size();
    }

    /** The levels the game data defines (20, 30 ...). */
    public int getDataMaxLevel() {
        return effects.size();
    }

    public boolean isFourthJob() {
        if (job == 2212) {
            return false;
        }
        if (id == 22170001 || id == 22171003 || id == 22171004 || id == 22181002 || id == 22181003) {
            return true;
        }
        return job % 10 == 2;
    }

    public void setElement(Element elem) {
        element = elem;
    }

    public Element getElement() {
        return element;
    }

    public int getAnimationTime() {
        return animationTime;
    }

    public void setAnimationTime(int time) {
        animationTime = time;
    }

    public void incAnimationTime(int time) {
        animationTime += time;
    }

    public boolean isBeginnerSkill() {
        return id % 10000000 < 10000;
    }

    public void setAction(boolean act) {
        action = act;
    }

    public boolean getAction() {
        return action;
    }

    public void addLevelEffect(StatEffect effect) {
        effects.add(effect);
    }
}