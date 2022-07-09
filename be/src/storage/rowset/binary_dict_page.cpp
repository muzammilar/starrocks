// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/olap/rowset/segment_v2/binary_dict_page.cpp

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

#include "storage/rowset/binary_dict_page.h"

#include <memory>
#include <type_traits>

#include "common/logging.h"
#include "gutil/casts.h"
#include "gutil/strings/substitute.h" // for Substitute
#include "storage/chunk_helper.h"
#include "storage/range.h"
#include "storage/rowset/bitshuffle_page.h"
#include "util/slice.h" // for Slice
#include "util/unaligned_access.h"

namespace starrocks {

using strings::Substitute;

BinaryDictPageBuilder::BinaryDictPageBuilder(const PageBuilderOptions& options)
        : _options(options),
          _finished(false),
          _data_page_builder(nullptr),
          _dict_builder(nullptr),
          _encoding_type(DICT_ENCODING) {
    // initially use DICT_ENCODING
    _data_page_builder = std::make_unique<BitshufflePageBuilder<OLAP_FIELD_TYPE_INT>>(options);
    _data_page_builder->reserve_head(BINARY_DICT_PAGE_HEADER_SIZE);
    PageBuilderOptions dict_builder_options;
    dict_builder_options.data_page_size = _options.dict_page_size;
    _dict_builder = std::make_unique<BinaryPlainPageBuilder>(dict_builder_options);
    reset();
}

bool BinaryDictPageBuilder::is_page_full() {
    if (_data_page_builder->is_page_full()) {
        return true;
    }
    return _encoding_type == DICT_ENCODING && _dict_builder->is_page_full();
}

size_t BinaryDictPageBuilder::add(const uint8_t* vals, size_t count) {
    if (_encoding_type == DICT_ENCODING) {
        DCHECK(!_finished);
        DCHECK_GT(count, 0);
        const Slice* src = reinterpret_cast<const Slice*>(vals);
        uint32_t value_code = -1;
        // Manually devirtualization.
        auto* code_page = down_cast<BitshufflePageBuilder<OLAP_FIELD_TYPE_INT>*>(_data_page_builder.get());

        if (_data_page_builder->count() == 0) {
            auto s = unaligned_load<Slice>(src);
            _first_value.assign_copy(reinterpret_cast<const uint8_t*>(s.get_data()), s.get_size());
        }

        for (int i = 0; i < count; ++i, ++src) {
            auto s = unaligned_load<Slice>(src);
            auto iter = _dictionary.find(s);
            if (iter != _dictionary.end()) {
                value_code = iter->second;
            } else if (_dict_builder->add_slice(s)) {
                value_code = _dictionary.size();
                _dictionary.insert_or_assign(std::string(s.data, s.size), value_code);
            } else {
                return i;
            }
            if (code_page->add_one(reinterpret_cast<const uint8_t*>(&value_code)) < 1) {
                return i;
            }
        }
        return count;
    } else {
        DCHECK_EQ(_encoding_type, PLAIN_ENCODING);
        return _data_page_builder->add(vals, count);
    }
}

faststring* BinaryDictPageBuilder::finish() {
    DCHECK(!_finished);
    _finished = true;

    faststring* data_slice = _data_page_builder->finish();
    encode_fixed32_le(data_slice->data(), _encoding_type);
    return data_slice;
}

void BinaryDictPageBuilder::reset() {
    _finished = false;
    if (_encoding_type == DICT_ENCODING && _dict_builder->is_page_full()) {
        _data_page_builder = std::make_unique<BinaryPlainPageBuilder>(_options);
        _data_page_builder->reserve_head(BINARY_DICT_PAGE_HEADER_SIZE);
        _encoding_type = PLAIN_ENCODING;
    } else {
        _data_page_builder->reset();
    }
    _finished = false;
}

size_t BinaryDictPageBuilder::count() const {
    return _data_page_builder->count();
}

uint64_t BinaryDictPageBuilder::size() const {
    return _pool.total_allocated_bytes() + _data_page_builder->size();
}

faststring* BinaryDictPageBuilder::get_dictionary_page() {
    return _dict_builder->finish();
}

Status BinaryDictPageBuilder::get_first_value(void* value) const {
    DCHECK(_finished);
    if (_data_page_builder->count() == 0) {
        return Status::NotFound("page is empty");
    }
    if (_encoding_type != DICT_ENCODING) {
        return _data_page_builder->get_first_value(value);
    }
    *reinterpret_cast<Slice*>(value) = Slice(_first_value);
    return Status::OK();
}

Status BinaryDictPageBuilder::get_last_value(void* value) const {
    DCHECK(_finished);
    if (_data_page_builder->count() == 0) {
        return Status::NotFound("page is empty");
    }
    if (_encoding_type != DICT_ENCODING) {
        return _data_page_builder->get_last_value(value);
    }
    uint32_t value_code;
    RETURN_IF_ERROR(_data_page_builder->get_last_value(&value_code));
    *reinterpret_cast<Slice*>(value) = _dict_builder->get_value(value_code);
    return Status::OK();
}

bool BinaryDictPageBuilder::is_valid_global_dict(const vectorized::GlobalDictMap* global_dict) const {
    for (auto it = _dictionary.begin(); it != _dictionary.end(); ++it) {
        if (auto iter = global_dict->find(it->first); iter == global_dict->end()) {
            return false;
        }
    }
    return true;
}

template <FieldType Type>
BinaryDictPageDecoder<Type>::BinaryDictPageDecoder(Slice data, const PageDecoderOptions& options)
        : _data(data),
          _options(options),
          _data_page_decoder(nullptr),
          _parsed(false),
          _encoding_type(UNKNOWN_ENCODING) {}

template <FieldType Type>
Status BinaryDictPageDecoder<Type>::init() {
    CHECK(!_parsed);
    if (_data.size < BINARY_DICT_PAGE_HEADER_SIZE) {
        return Status::Corruption(
                strings::Substitute("invalid data size:$0, header size:$1", _data.size, BINARY_DICT_PAGE_HEADER_SIZE));
    }
    size_t type = decode_fixed32_le((const uint8_t*)&_data.data[0]);
    _encoding_type = static_cast<EncodingTypePB>(type);
    _data.remove_prefix(BINARY_DICT_PAGE_HEADER_SIZE);
    if (_encoding_type == DICT_ENCODING) {
        // copy the codewords into a temporary buffer first
        // And then copy the strings corresponding to the codewords to the destination buffer
        const TypeInfoPtr& type_info = get_type_info(OLAP_FIELD_TYPE_INT);

        RETURN_IF_ERROR(ColumnVectorBatch::create(0, false, type_info, nullptr, &_batch));
        _data_page_decoder = std::make_unique<BitShufflePageDecoder<OLAP_FIELD_TYPE_INT>>(_data, _options);
    } else if (_encoding_type == PLAIN_ENCODING) {
        DCHECK_EQ(_encoding_type, PLAIN_ENCODING);
        _data_page_decoder.reset(new BinaryPlainPageDecoder<Type>(_data, _options));
    } else {
        LOG(WARNING) << "invalid encoding type:" << _encoding_type;
        return Status::Corruption(strings::Substitute("invalid encoding type:$0", _encoding_type));
    }

    RETURN_IF_ERROR(_data_page_decoder->init());
    _parsed = true;
    return Status::OK();
}

template <FieldType Type>
Status BinaryDictPageDecoder<Type>::seek_to_position_in_page(size_t pos) {
    return _data_page_decoder->seek_to_position_in_page(pos);
}

template <FieldType Type>
void BinaryDictPageDecoder<Type>::set_dict_decoder(PageDecoder* dict_decoder) {
    _dict_decoder = down_cast<BinaryPlainPageDecoder<Type>*>(dict_decoder);
    _max_value_legth = _dict_decoder->max_value_length();
}

template <FieldType Type>
Status BinaryDictPageDecoder<Type>::next_batch(size_t* n, ColumnBlockView* dst) {
    if (_encoding_type == PLAIN_ENCODING) {
        return _data_page_decoder->next_batch(n, dst);
    }
    // dictionary encoding
    DCHECK(_parsed);
    DCHECK(_dict_decoder != nullptr) << "dict decoder pointer is nullptr";
    if (PREDICT_FALSE(*n == 0)) {
        *n = 0;
        return Status::OK();
    }
    Slice* out = reinterpret_cast<Slice*>(dst->data());
    _batch->resize(*n);

    ColumnBlock column_block(_batch.get(), dst->column_block()->pool());
    ColumnBlockView tmp_block_view(&column_block);
    RETURN_IF_ERROR(_data_page_decoder->next_batch(n, &tmp_block_view));
    for (int i = 0; i < *n; ++i) {
        int32_t codeword = *reinterpret_cast<const int32_t*>(column_block.cell_ptr(i));
        // get the string from the dict decoder
        Slice element = _dict_decoder->string_at_index(codeword);
        if (element.size > 0) {
            char* destination = (char*)dst->column_block()->pool()->allocate(element.size);
            if (destination == nullptr) {
                return Status::MemoryAllocFailed(strings::Substitute("memory allocate failed, size:$0", element.size));
            }
            element.relocate(destination);
        }
        *out = element;
        ++out;
    }
    return Status::OK();
}

template <FieldType Type>
Status BinaryDictPageDecoder<Type>::next_batch(size_t* n, vectorized::Column* dst) {
    vectorized::SparseRange read_range;
    size_t begin = current_index();
    read_range.add(vectorized::Range(begin, begin + *n));
    RETURN_IF_ERROR(next_batch(read_range, dst));
    *n = current_index() - begin;
    return Status::OK();
}

template <FieldType Type>
Status BinaryDictPageDecoder<Type>::next_batch(const vectorized::SparseRange& range, vectorized::Column* dst) {
    if (_encoding_type == PLAIN_ENCODING) {
        return _data_page_decoder->next_batch(range, dst);
    }

    DCHECK(_parsed);
    DCHECK(_dict_decoder != nullptr) << "dict decoder pointer is nullptr";
    if (_vec_code_buf == nullptr) {
        _vec_code_buf = ChunkHelper::column_from_field_type(OLAP_FIELD_TYPE_INT, false);
    }
    _vec_code_buf->resize(0);
    _vec_code_buf->reserve(range.span_size());

    RETURN_IF_ERROR(_data_page_decoder->next_batch(range, _vec_code_buf.get()));
    size_t nread = _vec_code_buf->size();
    using cast_type = CppTypeTraits<OLAP_FIELD_TYPE_INT>::CppType;
    const cast_type* codewords = reinterpret_cast<const cast_type*>(_vec_code_buf->raw_data());
    std::vector<Slice> slices;
    slices.reserve(nread);
    if constexpr (Type == OLAP_FIELD_TYPE_CHAR) {
        for (int i = 0; i < nread; ++i) {
            Slice element = _dict_decoder->string_at_index(codewords[i]);
            // Strip trailing '\x00'
            element.size = strnlen(element.data, element.size);
            slices.emplace_back(element);
        }
    } else {
        for (int i = 0; i < nread; ++i) {
            slices.emplace_back(_dict_decoder->string_at_index(codewords[i]));
        }
    }

    CHECK(dst->append_strings_overflow(slices, _max_value_legth));
    return Status::OK();
}

template <FieldType Type>
Status BinaryDictPageDecoder<Type>::next_dict_codes(size_t* n, vectorized::Column* dst) {
    DCHECK(_encoding_type == DICT_ENCODING);
    DCHECK(_parsed);
    return _data_page_decoder->next_batch(n, dst);
}

template <FieldType Type>
Status BinaryDictPageDecoder<Type>::next_dict_codes(const vectorized::SparseRange& range, vectorized::Column* dst) {
    DCHECK(_encoding_type == DICT_ENCODING);
    DCHECK(_parsed);
    return _data_page_decoder->next_batch(range, dst);
}

template class BinaryDictPageDecoder<OLAP_FIELD_TYPE_CHAR>;
template class BinaryDictPageDecoder<OLAP_FIELD_TYPE_VARCHAR>;

} // namespace starrocks
