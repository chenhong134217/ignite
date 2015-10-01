/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
#include "ignite/portable/portable_containers.h"

using namespace ignite::impl::portable;

namespace ignite
{
    namespace portable
    {
        PortableStringArrayWriter::PortableStringArrayWriter(PortableWriterImpl* impl, int32_t id) : 
            impl(impl), id(id)
        {
            // No-op.
        }

        void PortableStringArrayWriter::Write(const char* val)
        {
            if (val)
                Write(val, static_cast<int32_t>(strlen(val)));
            else
                Write(NULL, -1);
        }

        void PortableStringArrayWriter::Write(const char* val, int32_t len)
        {
            impl->WriteStringElement(id, val, len);
        }

        void PortableStringArrayWriter::Close()
        {
            impl->CommitContainer(id);
        }

        PortableStringArrayReader::PortableStringArrayReader(impl::portable::PortableReaderImpl* impl, 
            int32_t id, int32_t size) : impl(impl), id(id), size(size)
        {
            // No-op.
        }

        bool PortableStringArrayReader::HasNext()
        {
            return impl->HasNextElement(id);
        }

        int32_t PortableStringArrayReader::GetNext(char* res, int32_t len)
        {
            return impl->ReadStringElement(id, res, len);
        }

        int32_t PortableStringArrayReader::GetSize() const
        {
            return size;
        }

        bool PortableStringArrayReader::IsNull() const
        {
            return size == -1;
        }
    }
}