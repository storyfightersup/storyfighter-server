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

/** A skill macro: up to SKILLS skills cast one after another from one key. OpenStory shows 5 macros x 6 skills. */
public class SkillMacro {
    public static final int SKILLS = 6;

    private final int[] skills = new int[SKILLS];
    private final String name;
    private final int shout;
    private final int position;

    public SkillMacro(int[] skills, String name, int shout, int position) {
        for (int i = 0; i < SKILLS && i < skills.length; i++) {
            this.skills[i] = skills[i];
        }
        this.name = name;
        this.shout = shout;
        this.position = position;
    }

    public int getSkill(int index) {
        return skills[index];
    }

    public void setSkill(int index, int skill) {
        skills[index] = skill;
    }

    public String getName() {
        return name;
    }

    public int getShout() {
        return shout;
    }

    public int getPosition() {
        return position;
    }
}
