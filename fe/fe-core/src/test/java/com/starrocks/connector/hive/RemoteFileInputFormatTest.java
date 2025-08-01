// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.connector.hive;

import com.starrocks.thrift.THdfsFileFormat;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RemoteFileInputFormatTest {
    @Test
    public void testParquetFormat() {
        Assertions.assertSame(RemoteFileInputFormat.PARQUET, RemoteFileInputFormat
                .fromHdfsInputFormatClass("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat"));
        Assertions.assertSame(RemoteFileInputFormat.ORC,
                RemoteFileInputFormat.fromHdfsInputFormatClass("org.apache.hadoop.hive.ql.io.orc.OrcInputFormat"));
    }

    @Test
    public void testUnknownFormat() {
        RemoteFileInputFormat format = RemoteFileInputFormat.UNKNOWN;
        Assertions.assertEquals(THdfsFileFormat.UNKNOWN, format.toThrift());
    }
}
