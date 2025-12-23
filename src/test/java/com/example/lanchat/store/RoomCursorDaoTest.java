package com.example.lanchat.store;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RoomCursorDaoTest {

    @After
    public void tearDown() {
        Db.close();
    }

    @Test
    public void cursorIsMonotonic() throws Exception {
        Path db = Files.createTempFile("lanchat-test-", ".db");
        Db.init(db.toString());

        RoomCursorDao dao = new RoomCursorDao();
        String roomId = "room-1";

        assertEquals("0", dao.getCursor(roomId));
        dao.updateCursorMonotonic(roomId, "5");
        assertEquals("5", dao.getCursor(roomId));

        dao.updateCursorMonotonic(roomId, "3");
        assertEquals("5", dao.getCursor(roomId));

        dao.updateCursorMonotonic(roomId, "9");
        assertEquals("9", dao.getCursor(roomId));
    }
}

