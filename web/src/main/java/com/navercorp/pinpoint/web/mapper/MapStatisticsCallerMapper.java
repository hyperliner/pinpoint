/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.web.mapper;

import java.util.*;

import com.navercorp.pinpoint.common.buffer.Buffer;
import com.navercorp.pinpoint.common.buffer.FixedBuffer;
import com.navercorp.pinpoint.common.buffer.OffsetFixedBuffer;
import com.navercorp.pinpoint.common.hbase.HBaseTables;
import com.navercorp.pinpoint.common.util.ApplicationMapStatisticsUtils;
import com.navercorp.pinpoint.common.util.TimeUtils;
import com.navercorp.pinpoint.web.applicationmap.rawdata.LinkDataMap;
import com.navercorp.pinpoint.web.vo.Application;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.hadoop.hbase.RowMapper;
import org.springframework.stereotype.Component;

/**
 * rowkey = caller col = callee
 * 
 * @author netspider
 * 
 */
@Component
public class MapStatisticsCallerMapper implements RowMapper<LinkDataMap> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final LinkFilter filter;

    public MapStatisticsCallerMapper() {
        this(SkipLinkFilter.FILTER);
    }

    public MapStatisticsCallerMapper(LinkFilter filter) {
        if (filter == null) {
            throw new NullPointerException("filter must not be null");
        }
        this.filter = filter;
    }

    @Override
    public LinkDataMap mapRow(Result result, int rowNum) throws Exception {
        if (result.isEmpty()) {
            return new LinkDataMap();
        }
        logger.debug("mapRow:{}", rowNum);

        final Buffer row = new FixedBuffer(result.getRow());
        final Application caller = readCallerApplication(row);
        final long timestamp = TimeUtils.recoveryTimeMillis(row.readLong());

        // key is destApplicationName.
        final LinkDataMap linkDataMap = new LinkDataMap();
        for (KeyValue kv :  result.raw()) {
            final byte[] family = kv.getFamily();
            if (Bytes.equals(family, HBaseTables.MAP_STATISTICS_CALLEE_CF_COUNTER)) {
                final byte[] qualifier = kv.getQualifier();
                final Application callee = readCalleeApplication(qualifier);
                if (filter.filter(callee)) {
                    continue;
                }

                long requestCount = getValueToLong(kv);

                short histogramSlot = ApplicationMapStatisticsUtils.getHistogramSlotFromColumnName(qualifier);
                boolean isError = histogramSlot == (short) -1;

                String calleeHost = ApplicationMapStatisticsUtils.getHost(qualifier);

                if (logger.isDebugEnabled()) {
                    logger.debug("    Fetched Caller.  {} -> {} (slot:{}/{}) calleeHost:{}", caller, callee, histogramSlot, requestCount, calleeHost);
                }

                final short slotTime = (isError) ? (short) -1 : histogramSlot;
                if (StringUtils.isEmpty(calleeHost)) {
                    calleeHost = callee.getName();
                }
                linkDataMap.addLinkData(caller, caller.getName(), callee, calleeHost, timestamp, slotTime, requestCount);


            } else if (Bytes.equals(family, HBaseTables.MAP_STATISTICS_CALLEE_CF_VER2_COUNTER)) {

                final Buffer buffer = new OffsetFixedBuffer(kv.getBuffer(), kv.getQualifierOffset());
                final Application callee = readCalleeApplication(buffer);
                if (filter.filter(callee)) {
                    continue;
                }

                String calleeHost = buffer.readPrefixedString();
                short histogramSlot = buffer.readShort();

                boolean isError = histogramSlot == (short) -1;

                String callerAgentId = buffer.readPrefixedString();

                long requestCount = getValueToLong(kv);
                if (logger.isDebugEnabled()) {
                    logger.debug("    Fetched Caller.(New) {} {} -> {} (slot:{}/{}) calleeHost:{}", caller, callerAgentId, callee, histogramSlot, requestCount, calleeHost);
                }

                final short slotTime = (isError) ? (short) -1 : histogramSlot;
                if (StringUtils.isEmpty(calleeHost)) {
                    calleeHost = callee.getName();
                }
                linkDataMap.addLinkData(caller, callerAgentId, callee, calleeHost, timestamp, slotTime, requestCount);
            } else {
                throw new IllegalArgumentException("unknown ColumnFamily :" + Arrays.toString(family));
            }

        }

        return linkDataMap;
    }

    private long getValueToLong(KeyValue kv) {
        return Bytes.toLong(kv.getBuffer(), kv.getValueOffset());
    }


    private Application readCalleeApplication(byte[] qualifier) {
        String calleeApplicationName = ApplicationMapStatisticsUtils.getDestApplicationNameFromColumnName(qualifier);
        short calleeServiceType = ApplicationMapStatisticsUtils.getDestServiceTypeFromColumnName(qualifier);
        return new Application(calleeApplicationName, calleeServiceType);
    }


    private Application readCalleeApplication(Buffer buffer) {
        short calleeServiceType = buffer.readShort();
        String calleeApplicationName = buffer.readPrefixedString();
        return new Application(calleeApplicationName, calleeServiceType);
    }

    private Application readCallerApplication(Buffer row) {
        String callerApplicationName = row.read2PrefixedString();
        short callerServiceType = row.readShort();
        return new Application(callerApplicationName, callerServiceType);
    }
}
