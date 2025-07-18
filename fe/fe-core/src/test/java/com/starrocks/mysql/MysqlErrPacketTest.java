// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.mysql;

import com.starrocks.qe.QueryState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

public class MysqlErrPacketTest {
    private MysqlCapability capability;

    @BeforeEach
    public void setUp() {
        capability = new MysqlCapability(MysqlCapability.Flag.CLIENT_PROTOCOL_41.getFlagBit());
    }

    @Test
    public void testWrite() {
        MysqlSerializer serializer = MysqlSerializer.newInstance(capability);
        QueryState state = new QueryState();
        state.setError("error");

        MysqlErrPacket packet = new MysqlErrPacket(state);
        packet.writeTo(serializer);
        ByteBuffer buffer = serializer.toByteBuffer();

        // assert indicator
        Assertions.assertEquals(0xff, MysqlCodec.readInt1(buffer));
        // error code
        Assertions.assertEquals(1064, MysqlCodec.readInt2(buffer));
        // sql state marker
        Assertions.assertEquals('#', MysqlCodec.readInt1(buffer));
        // sql state
        Assertions.assertEquals("HY000", new String(MysqlCodec.readFixedString(buffer, 5)));
        // sql state
        Assertions.assertEquals("error", new String(MysqlCodec.readEofString(buffer)));

        Assertions.assertEquals(0, buffer.remaining());
    }

    @Test
    public void testWriteNullMsg() {
        MysqlSerializer serializer = MysqlSerializer.newInstance(capability);
        QueryState state = new QueryState();

        MysqlErrPacket packet = new MysqlErrPacket(state);
        packet.writeTo(serializer);
        ByteBuffer buffer = serializer.toByteBuffer();

        // assert indicator
        Assertions.assertEquals(0xff, MysqlCodec.readInt1(buffer));
        // error code
        Assertions.assertEquals(1064, MysqlCodec.readInt2(buffer));
        // sql state marker
        Assertions.assertEquals('#', MysqlCodec.readInt1(buffer));
        // sql state
        Assertions.assertEquals("HY000", new String(MysqlCodec.readFixedString(buffer, 5)));
        // sql state
        // NOTE: we put one space if MysqlErrPacket's errorMessage is null or empty
        Assertions.assertEquals("Unknown error", new String(MysqlCodec.readEofString(buffer)));

        Assertions.assertEquals(0, buffer.remaining());
    }

}

