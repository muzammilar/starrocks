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

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/test/util/string_util_test.cpp

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

#include "util/string_util.h"

#include <gtest/gtest.h>

#include "util/cpu_info.h"

namespace starrocks {

class StringUtilTest : public testing::Test {
public:
    StringUtilTest() = default;
    ~StringUtilTest() override = default;
};

TEST_F(StringUtilTest, normal) {
    {
        StringCaseSet test_set;
        test_set.emplace("AbC");
        test_set.emplace("AbCD");
        test_set.emplace("AbCE");
        ASSERT_EQ(1, test_set.count("abc"));
        ASSERT_EQ(1, test_set.count("abcd"));
        ASSERT_EQ(1, test_set.count("abce"));
        ASSERT_EQ(0, test_set.count("ab"));
    }
    {
        StringCaseUnorderedSet test_set;
        test_set.emplace("AbC");
        test_set.emplace("AbCD");
        test_set.emplace("AbCE");
        ASSERT_EQ(1, test_set.count("abc"));
        ASSERT_EQ(0, test_set.count("ab"));
    }
    {
        StringCaseMap<int> test_map;
        test_map.emplace("AbC", 123);
        test_map.emplace("AbCD", 234);
        test_map.emplace("AbCE", 345);
        ASSERT_EQ(123, test_map["abc"]);
        ASSERT_EQ(234, test_map["aBcD"]);
        ASSERT_EQ(345, test_map["abcE"]);
        ASSERT_EQ(0, test_map.count("ab"));
    }
    {
        StringCaseUnorderedMap<int> test_map;
        test_map.emplace("AbC", 123);
        test_map.emplace("AbCD", 234);
        test_map.emplace("AbCE", 345);
        ASSERT_EQ(123, test_map["abc"]);
        ASSERT_EQ(234, test_map["aBcD"]);
        ASSERT_EQ(345, test_map["abcE"]);
        ASSERT_EQ(0, test_map.count("ab"));
    }
    {
        std::string ip1 = "192.168.1.1";
        std::string ip2 = "192.168.1.2";
        int64_t hash1 = hash_of_path(ip1, "/home/disk1/data2.HDD");
        int64_t hash2 = hash_of_path(ip1, "/home/disk1//data2.HDD/");
        int64_t hash3 = hash_of_path(ip1, "home/disk1/data2.HDD/");
        ASSERT_EQ(hash1, hash2);
        ASSERT_EQ(hash3, hash2);

        int64_t hash4 = hash_of_path(ip1, "/home/disk1/data2.HDD/");
        int64_t hash5 = hash_of_path(ip2, "/home/disk1/data2.HDD/");
        ASSERT_NE(hash4, hash5);

        int64_t hash6 = hash_of_path(ip1, "/");
        int64_t hash7 = hash_of_path(ip1, "//");
        ASSERT_EQ(hash6, hash7);
    }
}

TEST_F(StringUtilTest, test_is_string_heap_allocated) {
    std::string s1 = "hello";
    ASSERT_FALSE(is_string_heap_allocated(s1));
    std::string s2 = "he";
    ASSERT_FALSE(is_string_heap_allocated(s2));
    std::string s3 = std::string(100, 'a'); // large enough to be heap allocated
    ASSERT_TRUE(is_string_heap_allocated(s3));
}

} // namespace starrocks
