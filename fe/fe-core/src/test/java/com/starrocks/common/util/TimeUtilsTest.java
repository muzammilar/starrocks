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

package com.starrocks.common.util;

import com.starrocks.analysis.DateLiteral;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.FeConstants;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static com.starrocks.common.util.TimeUtils.DATETIME_WITH_TIME_ZONE_PATTERN;

public class TimeUtilsTest {

    @Mocked
    TimeUtils timeUtils;

    @BeforeEach
    public void setUp() {
        TimeZone tz = TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai"));
        new Expectations(timeUtils) {
            {
                TimeUtils.getTimeZone();
                minTimes = 0;
                result = tz;
            }
        };
    }

    @Test
    public void testNormal() {
        Assertions.assertNotNull(TimeUtils.getCurrentFormatTime());
        Assertions.assertTrue(TimeUtils.getEstimatedTime(0L) > 0);

        Assertions.assertEquals(-62167420800000L, TimeUtils.MIN_DATE.getTime());
        Assertions.assertEquals(253402185600000L, TimeUtils.MAX_DATE.getTime());
        Assertions.assertEquals(-62167420800000L, TimeUtils.MIN_DATETIME.getTime());
        Assertions.assertEquals(253402271999000L, TimeUtils.MAX_DATETIME.getTime());
    }

    @Test
    public void testDateParse() {
        // date
        List<String> validDateList = new LinkedList<>();
        validDateList.add("2013-12-02");
        validDateList.add("2013-12-02");
        validDateList.add("2013-12-2");
        validDateList.add("2013-12-2");
        validDateList.add("9999-12-31");
        validDateList.add("1900-01-01");
        validDateList.add("2013-2-28");
        validDateList.add("0000-01-01");
        for (String validDate : validDateList) {
            try {
                TimeUtils.parseDate(validDate, PrimitiveType.DATE);
            } catch (AnalysisException e) {
                e.printStackTrace();
                System.out.println(validDate);
                Assertions.fail();
            }
        }

        List<String> invalidDateList = new LinkedList<>();
        invalidDateList.add("2013-12-02 ");
        invalidDateList.add(" 2013-12-02");
        invalidDateList.add("20131-2-28");
        invalidDateList.add("a2013-2-28");
        invalidDateList.add("2013-22-28");
        invalidDateList.add("2013-2-29");
        invalidDateList.add("2013-2-28 2:3:4");
        for (String invalidDate : invalidDateList) {
            try {
                TimeUtils.parseDate(invalidDate, PrimitiveType.DATE);
                Assertions.fail();
            } catch (AnalysisException e) {
                Assertions.assertTrue(e.getMessage().contains("Invalid"));
            }
        }

        // datetime
        List<String> validDateTimeList = new LinkedList<>();
        validDateTimeList.add("2013-12-02 13:59:59");
        validDateTimeList.add("2013-12-2 13:59:59");
        validDateTimeList.add("2013-12-2 1:59:59");
        validDateTimeList.add("2013-12-2 3:1:1");
        validDateTimeList.add("9999-12-31 23:59:59");
        validDateTimeList.add("1900-01-01 00:00:00");
        validDateTimeList.add("2013-2-28 23:59:59");
        validDateTimeList.add("2013-2-28 2:3:4");
        validDateTimeList.add("2014-05-07 19:8:50");
        validDateTimeList.add("0000-01-01 00:00:00");
        for (String validDateTime : validDateTimeList) {
            try {
                TimeUtils.parseDate(validDateTime, PrimitiveType.DATETIME);
            } catch (AnalysisException e) {
                e.printStackTrace();
                System.out.println(validDateTime);
                Assertions.fail();
            }
        }

        List<String> invalidDateTimeList = new LinkedList<>();
        invalidDateTimeList.add("2013-12-02  12:12:10");
        invalidDateTimeList.add(" 2013-12-02 12:12:10 ");
        invalidDateTimeList.add("20131-2-28 12:12:10");
        invalidDateTimeList.add("a2013-2-28 12:12:10");
        invalidDateTimeList.add("2013-22-28 12:12:10");
        invalidDateTimeList.add("2013-2-29 12:12:10");
        invalidDateTimeList.add("2013-2-28");
        invalidDateTimeList.add("2013-13-01 12:12:12");
        for (String invalidDateTime : invalidDateTimeList) {
            try {
                TimeUtils.parseDate(invalidDateTime, PrimitiveType.DATETIME);
                Assertions.fail();
            } catch (AnalysisException e) {
                Assertions.assertTrue(e.getMessage().contains("Invalid"));
            }
        }
    }

    @Test
    public void testDateTrans() throws AnalysisException {
        Assertions.assertEquals(FeConstants.NULL_STRING, TimeUtils.longToTimeString(-2));

        long timestamp = 1426125600000L;
        Assertions.assertEquals("2015-03-12 10:00:00", TimeUtils.longToTimeString(timestamp));

        DateLiteral date = new DateLiteral("2015-03-01", ScalarType.DATE);
        Assertions.assertEquals(20150301000000L, date.getLongValue());

        DateLiteral datetime = new DateLiteral("2015-03-01 12:00:00", ScalarType.DATETIME);
        Assertions.assertEquals(20150301120000L, datetime.getLongValue());
    }

    @Test
    public void testTimezone() throws AnalysisException {
        try {
            Assertions.assertEquals("CST", TimeUtils.checkTimeZoneValidAndStandardize("CST"));
            Assertions.assertEquals("+08:00", TimeUtils.checkTimeZoneValidAndStandardize("+08:00"));
            Assertions.assertEquals("+08:00", TimeUtils.checkTimeZoneValidAndStandardize("+8:00"));
            Assertions.assertEquals("-08:00", TimeUtils.checkTimeZoneValidAndStandardize("-8:00"));
            Assertions.assertEquals("+08:00", TimeUtils.checkTimeZoneValidAndStandardize("8:00"));
        } catch (DdlException ex) {
            Assertions.fail();
        }
        try {
            TimeUtils.checkTimeZoneValidAndStandardize("FOO");
            Assertions.fail();
        } catch (DdlException ex) {
            Assertions.assertTrue(ex.getMessage().contains("Unknown or incorrect time zone: 'FOO'"));
        }
    }

    @Test
    public void testConvertTimeUnitValuetoSecond() {
        long dayRes = TimeUtils.convertTimeUnitValueToSecond(2, TimeUnit.DAYS);
        long hourRes = TimeUtils.convertTimeUnitValueToSecond(2, TimeUnit.HOURS);
        long minuteRes = TimeUtils.convertTimeUnitValueToSecond(2, TimeUnit.MINUTES);
        long secondRes = TimeUtils.convertTimeUnitValueToSecond(2, TimeUnit.SECONDS);
        long milRes = TimeUtils.convertTimeUnitValueToSecond(2, TimeUnit.MILLISECONDS);
        long micRes = TimeUtils.convertTimeUnitValueToSecond(2, TimeUnit.MICROSECONDS);
        long nanoRes = TimeUtils.convertTimeUnitValueToSecond(2, TimeUnit.NANOSECONDS);
        Assertions.assertEquals(dayRes, 2 * 24 * 60 * 60);
        Assertions.assertEquals(hourRes, 2 * 60 * 60);
        Assertions.assertEquals(minuteRes, 2 * 60);
        Assertions.assertEquals(secondRes, 2);
        Assertions.assertEquals(milRes, 2 / 1000);
        Assertions.assertEquals(micRes, 2 / 1000 / 1000);
        Assertions.assertEquals(nanoRes, 2 / 1000 / 1000 / 1000);
    }

    @Test
    public void testGetNextValidTimeSecond() {
        // 2022-04-21 20:45:11
        long startTimeSecond = 1650545111L;
        // 2022-04-21 23:32:11
        long targetTimeSecond = 1650555131L;
        try {
            TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond, 2, TimeUnit.NANOSECONDS);
        } catch (DdlException e) {
            Assertions.assertEquals("Can not get next valid time second," +
                    "startTimeSecond:1650545111 period:2 timeUnit:NANOSECONDS", e.getMessage());
        }
        try {
            TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond, 2, TimeUnit.MILLISECONDS);
        } catch (DdlException e) {
            Assertions.assertEquals("Can not get next valid time second," +
                    "startTimeSecond:1650545111 period:2 timeUnit:MILLISECONDS", e.getMessage());
        }
        try {
            // 2022-04-21 23:32:12
            Assertions.assertEquals(1650555132L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    1000, TimeUnit.MILLISECONDS));
            // 2022-04-21 23:32:12
            Assertions.assertEquals(1650555132L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    1, TimeUnit.SECONDS));
            // 2022-04-21 23:32:16
            Assertions.assertEquals(1650555136L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    5, TimeUnit.SECONDS));
            // 2022-04-21 23:32:15
            Assertions.assertEquals(1650555135L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    7, TimeUnit.SECONDS));
            // 2022-04-21 23:32:12
            Assertions.assertEquals(1650555132L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    11, TimeUnit.SECONDS));
            // 2022-04-21 23:33:31
            Assertions.assertEquals(1650555211L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    101, TimeUnit.SECONDS));
            // 2022-04-21 23:48:20
            Assertions.assertEquals(1650556100L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    999, TimeUnit.SECONDS));
            // 2022-04-21 23:45:11
            Assertions.assertEquals(1650555911L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    3, TimeUnit.HOURS));
            // 2022-04-22 03:45:11
            Assertions.assertEquals(1650570311L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    7, TimeUnit.HOURS));
            // 2022-04-30 20:45:11
            Assertions.assertEquals(1651322711L, TimeUtils.getNextValidTimeSecond(startTimeSecond, targetTimeSecond,
                    9, TimeUnit.DAYS));
            // 2022-04-21 23:32:18
            Assertions.assertEquals(1650555138L, TimeUtils.getNextValidTimeSecond(1650555138L, targetTimeSecond,
                    9, TimeUnit.DAYS));
        } catch (DdlException e) {
            Assertions.fail(e.getMessage());
        }
    }
    
    @Test
    public void testDateTimeWithTimeZonePattern() {
        // Case1: date time string is '2024-09-10 Asia/Shanghai'
        String value1 = "2024-09-10 Asia/Shanghai";
        Matcher matcher1 = DATETIME_WITH_TIME_ZONE_PATTERN.matcher(value1);
        Assertions.assertTrue(matcher1.matches());
        Assertions.assertEquals("2024", matcher1.group("year"));
        Assertions.assertEquals("09", matcher1.group("month"));
        Assertions.assertEquals("10", matcher1.group("day"));
        Assertions.assertEquals("Asia/Shanghai", matcher1.group("timezone"));
        Assertions.assertNull(matcher1.group("hour"));
        Assertions.assertNull(matcher1.group("minute"));
        Assertions.assertNull(matcher1.group("second"));
        Assertions.assertNull(matcher1.group("fraction"));
    
        // Case2: date time string is '2024-09-10 01:01:01.123 Asia/Shanghai'
        String value2 = "2024-09-10 01:01:01.123 Asia/Shanghai";
        Matcher matcher2 = DATETIME_WITH_TIME_ZONE_PATTERN.matcher(value2);
        Assertions.assertTrue(matcher2.matches());
        Assertions.assertEquals("2024", matcher2.group("year"));
        Assertions.assertEquals("09", matcher2.group("month"));
        Assertions.assertEquals("10", matcher2.group("day"));
        Assertions.assertEquals("01", matcher2.group("hour"));
        Assertions.assertEquals("01", matcher2.group("minute"));
        Assertions.assertEquals("01", matcher2.group("second"));
        Assertions.assertEquals("123", matcher2.group("fraction"));
        Assertions.assertEquals("Asia/Shanghai", matcher2.group("timezone"));
    
        // Case3: date time string is ' 2024-09-10'. It will not match
        String value3 = " 2024-09-10";
        Matcher matcher3 = DATETIME_WITH_TIME_ZONE_PATTERN.matcher(value3);
        Assertions.assertFalse(matcher3.matches());
    }
}
