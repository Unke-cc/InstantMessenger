package com.example.lanchat.service;

import com.example.lanchat.store.ConversationDao;
import com.example.lanchat.store.ConversationDao.Conversation;
import java.sql.SQLException;

public class ConversationService {

    private final ConversationDao conversationDao;

    public ConversationService() {
        this.conversationDao = new ConversationDao();
    }

    public Conversation getOrCreatePrivateConv(String peerNodeId, String title, long now) throws SQLException {
        return conversationDao.getOrCreatePrivate(peerNodeId, title, now);
    }

    public void touch(String convId, long lastMsgTs) throws SQLException {
        conversationDao.updateLastMsgTs(convId, lastMsgTs);
    }
}

