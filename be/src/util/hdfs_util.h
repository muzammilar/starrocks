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

#pragma once

#include <string>

#include "common/status.h"

namespace starrocks {

// Translate hdfs errno to Status
Status hdfs_error_to_status(const std::string& context, int err_number);

std::string get_hdfs_err_msg(int err);

std::string get_hdfs_err_msg();

Status get_namenode_from_path(const std::string& path, std::string* namenode);

} // namespace starrocks
