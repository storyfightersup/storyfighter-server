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
package net.server.handlers.login;

import client.Client;
import config.YamlConfig;
import net.PacketHandler;
import net.packet.InPacket;
import net.server.Server;
import net.server.coordinator.session.Hwid;
import tools.BCrypt;
import tools.DatabaseConnection;
import tools.HexTool;
import tools.PacketCreator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Calendar;

public final class LoginPasswordHandler implements PacketHandler {

    @Override
    public boolean validateState(Client c) {
        return !c.isLoggedIn();
    }

    @Override
    public final void handlePacket(InPacket p, Client c) {
        String remoteHost = c.getRemoteAddress();
        if (remoteHost.contentEquals("null")) {
            c.sendPacket(PacketCreator.getLoginFailed(14));          // thanks Alchemist for noting remoteHost could be null
            return;
        }

        String login = p.readString();
        String pwd = p.readString();
        c.setAccountName(login);

        p.skip(6);   // localhost masked the initial part with zeroes...
        byte[] hwidNibbles = p.readBytes(4);
        Hwid hwid = new Hwid(HexTool.toCompactHexString(hwidNibbles));
        server.starter.LoginTiming timing = server.starter.LoginTiming.start(login);   // StoryFighter: time the login steps (slow first login, 2026-10-22)
        int loginok = c.login(login, pwd, hwid);
        timing.step("password check");


        // StoryFighter (docs/SIGNUP.md): no account is ever made at login, whatever AUTOMATIC_REGISTER says. An unknown
        // name gets loginok 5 = the normal "not registered" answer; accounts come only from the sign-up window
        // (server.starter.StorySignup, STORY_SIGNUP).

        if (YamlConfig.config.server.BCRYPT_MIGRATION && (loginok <= -10)) { // -10 means migration to bcrypt, -23 means TOS wasn't accepted
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("UPDATE accounts SET password = ? WHERE name = ?;")) {
                ps.setString(1, BCrypt.hashpw(pwd, BCrypt.gensalt(12)));
                ps.setString(2, login);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            } finally {
                loginok = (loginok == -10) ? 0 : 23;
            }
        }

        // StoryFighter (docs/MODERATION.md): a banned PC id refuses every account on that PC (c.login set the hwid)
        if (c.hasBannedIP() || c.hasBannedMac() || c.hasBannedHWID()) {
            c.sendPacket(PacketCreator.getLoginFailed(3));
            return;
        }
        Calendar tempban = c.getTempBanCalendarFromDB();
        if (tempban != null) {
            if (tempban.getTimeInMillis() > Calendar.getInstance().getTimeInMillis()) {
                c.sendPacket(PacketCreator.getTempBan(tempban.getTimeInMillis(), c.getGReason()));
                return;
            }
        }
        if (loginok == 3) {
            c.sendPacket(PacketCreator.getPermBan(c.getGReason()));//crashes but idc :D
            return;
        } else if (loginok != 0) {
            c.sendPacket(PacketCreator.getLoginFailed(loginok));
            return;
        }
        timing.step("ban checks");
        if (c.finishLogin() == 0) {
            timing.step("finishLogin");
            c.checkChar(c.getAccID());
            timing.step("checkChar");
            login(c, timing);
        } else {
            c.sendPacket(PacketCreator.getLoginFailed(7));
        }
    }

    private static void login(Client c, server.starter.LoginTiming timing) {
        c.sendPacket(PacketCreator.getAuthSuccess(c, timing));//why the fk did I do c.getAccountName()?
        timing.step("auth packet sent");
        Server.getInstance().registerLoginState(c);
        timing.step("registerLoginState");
    }
}
