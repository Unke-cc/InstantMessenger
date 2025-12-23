package com.example.lanchat.store;

import com.example.lanchat.store.MessageDao.Message;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MessageDaoSyncQueryTest {

    @After
    public void tearDown() {
        Db.close();
    }

    @Test
    public void listRoomMessagesAfterClockOrdersByClock() throws Exception {
        Path db = Files.createTempFile("lanchat-test-", ".db");
        Db.init(db.toString());

        MessageDao dao = new MessageDao();
        String roomId = "room-1";
        String convId = "conv-1";

        insertRoomMsg(dao, "m1", convId, roomId, 1000, 1);
        insertRoomMsg(dao, "m2", convId, roomId, 1001, 2);
        insertRoomMsg(dao, "m3", convId, roomId, 1002, 3);

        List<Message> after1 = dao.listRoomMessagesAfterClock(roomId, "1", 10);
        assertEquals(2, after1.size());
        assertEquals("m2", after1.get(0).msgId);
        assertEquals("m3", after1.get(1).msgId);
    }

    private static void insertRoomMsg(MessageDao dao, String msgId, String convId, String roomId, long ts, long clock) throws Exception {
        Message m = new Message();
        m.msgId = msgId;
        m.convId = convId;
        m.chatType = "ROOM";
        m.roomId = roomId;
        m.direction = "IN";
        m.fromNodeId = "n1";
        m.toNodeId = null;
        m.content = "x";
        m.contentType = "text/plain";
        m.ts = ts;
        m.updatedAt = ts;
        m.clockValue = String.valueOf(clock);
        m.status = "DELIVERED";
        dao.insert(m);
    }
}

