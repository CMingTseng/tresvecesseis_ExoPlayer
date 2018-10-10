/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.source.rtsp;

import android.net.Uri;
import android.os.Handler;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.extractor.DefaultExtractorInput;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.ts.TsExtractor;
import com.google.android.exoplayer2.source.SampleQueue;
import com.google.android.exoplayer2.source.SampleStream;
import com.google.android.exoplayer2.source.SequenceableLoader;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.rtp.RtpPacket;
import com.google.android.exoplayer2.source.rtp.extractor.DefaultRtpExtractor;
import com.google.android.exoplayer2.source.rtp.extractor.RtpExtractorInput;
import com.google.android.exoplayer2.source.rtp.extractor.RtpMp2tExtractor;
import com.google.android.exoplayer2.source.rtp.format.RtpPayloadFormat;
import com.google.android.exoplayer2.source.rtp.rtcp.RtcpRrPacket;
import com.google.android.exoplayer2.source.rtp.upstream.RtcpIncomingReportSink;
import com.google.android.exoplayer2.source.rtp.upstream.RtcpOutgoingReportSink;
import com.google.android.exoplayer2.source.rtp.upstream.RtpDataSinkSource;
import com.google.android.exoplayer2.source.rtp.upstream.RtpInternalSamplesSink;
import com.google.android.exoplayer2.source.rtp.upstream.RtpInternalDataSource;
import com.google.android.exoplayer2.source.rtsp.message.InterleavedFrame;
import com.google.android.exoplayer2.source.rtsp.message.Transport;
import com.google.android.exoplayer2.source.rtsp.media.MediaFormat;
import com.google.android.exoplayer2.source.rtsp.media.MediaSession;
import com.google.android.exoplayer2.source.rtsp.media.MediaTrack;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.UdpDataSinkSource;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.ConditionVariable;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.TrackIdGenerator;
import com.google.android.exoplayer2.util.Util;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import static com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES;
import static com.google.android.exoplayer2.source.rtp.upstream.RtpDataSinkSource.FLAG_ENABLE_RTCP_FEEDBACK;
import static com.google.android.exoplayer2.source.rtp.upstream.RtpDataSinkSource.FLAG_FORCE_RTCP_MULTIPLEXING;

public final class RtspSampleStreamWrapper implements
    Loader.Callback<RtspSampleStreamWrapper.MediaStreamLoadable>,
    SequenceableLoader, ExtractorOutput,
    SampleQueue.UpstreamFormatChangedListener, MediaSession.EventListener {

    public interface EventListener {
        void onMediaStreamPrepareStarted(RtspSampleStreamWrapper stream);
        void onMediaStreamPrepareFailure(RtspSampleStreamWrapper stream);
        void onMediaStreamPrepareSuccess(RtspSampleStreamWrapper stream);
        void onMediaStreamPlaybackFailure(RtspSampleStreamWrapper stream);
    }

    private static final String IPV4_ANY_ADDR = "0.0.0.0";

    private final ConditionVariable loadCondition;

    private final long positionUs;
    private final Allocator allocator;
    private final int minLoadableRetryCount;
    private final MediaSession session;
    private final MediaTrack track;
    private final EventListener listener;
    private final Runnable maybeFinishPrepareRunnable;
    private final Handler handler;

    private SampleQueue[] sampleQueues;
    private int[] sampleQueueTrackIds;
    private int[] sampleQueueTrackTypes;
    private boolean sampleQueuesBuilt;
    private boolean playback;
    private boolean prepared;
    private int enabledSampleQueueCount;
    private int enabledTrackCount;
    private boolean released;

    private Loader loader;

    // Indexed by track (as exposed by this source).
    private TrackGroupArray trackGroups;

    // Indexed by track group.
    private boolean[] trackGroupEnabledStates;

    private long lastSeekPositionUs;
    private volatile long pendingResetPositionUs;
    private boolean loadingFinished;

    private int localPort;
    private int[] interleavedChannels;
    private MediaStreamLoadable loadable;

    private boolean pendingResetMediaStreamLoadable;

    private final TrackIdGenerator trackIdGenerator;
    private final DefaultExtractorsFactory defaultExtractorsFactory;

    public RtspSampleStreamWrapper(MediaSession session, MediaTrack track,
                                   TrackIdGenerator trackIdGenerator, long positionUs,
                                   EventListener listener, Allocator allocator,
                                   int minLoadableRetryCount) {
        this.session = session;
        this.track = track;
        this.trackIdGenerator = trackIdGenerator;
        this.positionUs = positionUs;
        this.listener = listener;
        this.allocator = allocator;
        this.minLoadableRetryCount = minLoadableRetryCount;

        handler = new Handler();

        loadCondition = new ConditionVariable();

        loader = new Loader("Loader:RtspSampleStreamWrapper");

        sampleQueueTrackIds = new int[0];
        sampleQueueTrackTypes = new int[0];
        sampleQueues = new SampleQueue[0];

        trackGroupEnabledStates = new boolean[0];

        defaultExtractorsFactory = new DefaultExtractorsFactory();

        inReportSink = new RtcpIncomingReportSink();
        samplesSink = new RtpInternalSamplesSink();

        outReportSink = new RtcpOutgoingReportSink();
        outReportSink.addListener(session);

        maybeFinishPrepareRunnable = new Runnable() {
            @Override
            public void run() {
                maybeFinishPrepare();
            }
        };

        lastSeekPositionUs = positionUs;
        pendingResetPositionUs = C.TIME_UNSET;

        session.addListener(this);
    }

    public void setInterleavedChannels(int[] interleavedChannels) {
        this.interleavedChannels = interleavedChannels;
    }

    public void prepare() {
        if (loadingFinished) {
            return;
        }

        Transport transport = track.format().transport();

        if (!prepared && !loader.isLoading()) {
            if (Transport.UDP.equals(transport.lowerTransport())) {
                startUdpMediaLoader();
            } else {
                startTcpMediaLoader();
            }

        } else if (prepared) {
            pendingResetMediaStreamLoadable = true;

            if (loader.isLoading()) {
                loader.cancelLoading();
                loader.release();
            }

            loader = new Loader("Loader:RtspSampleStreamWrapper");

            if (Transport.TCP.equals(transport.lowerTransport())) {
                startTcpMediaLoader();
            }
        }
    }

    public void playback() {
        if (loadingFinished || !prepared || playback) {
            return;
        }

        if (session.isNatRequired()) {
            final int NUM_TIMES_TO_SEND = 2;
            Transport transport = track.format().transport();

            if (transport.serverPort() != null && transport.serverPort().length > 0) {
                int port = Integer.parseInt(transport.serverPort()[0]);
                for (int count = 0; count < NUM_TIMES_TO_SEND; count++) {
                    if (Transport.RTP_PROTOCOL.equals(transport.transportProtocol())) {
                        sendRtpPunchPacket((transport.source() != null) ? transport.source() :
                                (transport.destination() != null) ? transport.destination() :
                                        Uri.parse(track.url()).getHost(), port);

                        if (transport.serverPort().length == 2 && session.isRtcpSupported() &&
                                !session.isRtcpMuxed()) {
                            int rtcpPort = Integer.parseInt(transport.serverPort()[1]);
                            sendRtcpPunchPacket((transport.source() != null) ? transport.source() :
                                    (transport.destination() != null) ? transport.destination() :
                                            Uri.parse(track.url()).getHost(), rtcpPort);
                        }

                    } else {
                        sendPunchPacket((transport.source() != null) ? transport.source() :
                                (transport.destination() != null) ? transport.destination() :
                                        Uri.parse(track.url()).getHost(), port);
                    }
                }
            }
        }

        continueLoading(lastSeekPositionUs);
    }

    private void sendPunchPacket(String host, int port) {
        try {
            byte[] punchMessage = "Dummy".getBytes();
            ((UdpDataSinkSource)loadable.dataSource).writeTo(punchMessage, 0, punchMessage.length,
                InetAddress.getByName(host), port);

        } catch (IOException ex) {

        }
    }

    private void sendRtpPunchPacket(String host, int port) {
        try {
            ((RtpDataSinkSource)loadable.dataSource).writeTo(new RtpPacket.Builder().build(),
                    InetAddress.getByName(host), port);

        } catch (IOException ex) {

        }
    }

    private void sendRtcpPunchPacket(String host, int port) {
        try {
            ((RtpDataSinkSource)loadable.dataSource).writeTo(new RtcpRrPacket.Builder().build(),
                    InetAddress.getByName(host), port);

        } catch (IOException ex) {

        }
    }

    public void onInterleavedFrame(InterleavedFrame interleavedFrame) {
        if (prepared && !loadingFinished && interleavedChannels != null) {
            byte[] buffer = interleavedFrame.getData();

            if (interleavedFrame.getChannel() == interleavedChannels[0]) {
                samplesSink.write(buffer, 0, buffer.length);

            } else if (interleavedChannels.length > 1 &&
                interleavedFrame.getChannel() == interleavedChannels[1]) {
                inReportSink.write(buffer, 0, buffer.length);
            }
        }
    }

    public MediaTrack getMediaTrack() { return track; }

    public int getLocalPort() {
        return localPort;
    }

    public void maybeThrowPrepareError() throws IOException {
        maybeThrowError();
    }

    public TrackGroupArray getTrackGroups() {
        return trackGroups;
    }

    public void discardBufferToEnd() {
        //lastSeekPositionUs = positionUs;
        for (SampleQueue sampleQueue : sampleQueues) {
            sampleQueue.discardToEnd();
        }
    }

    /**
     * Attempts to seek to the specified position in microseconds.
     *
     * @param positionUs The seek position in microseconds.
     * @return Whether the wrapper was reset, meaning the wrapped sample queues were reset. If false,
     *     an in-buffer seek was performed.
     */
    public boolean seekToUs(long positionUs) {
        lastSeekPositionUs = positionUs;
        /*if (sampleQueuesBuilt && seekInsideBufferUs(positionUs)) {
            return false;
        }*/

        // We were unable to seek within the buffer, so need discard to end.
        for (SampleQueue sampleQueue : sampleQueues) {
            sampleQueue.discardToEnd();
        }

        pendingResetPositionUs = positionUs;
        return true;
    }

    public void discardBuffer(long positionUs, boolean toKeyframe) {
        int sampleQueueCount = sampleQueues.length;
        for (int i = 0; i < sampleQueueCount; i++) {
            sampleQueues[i].discardTo(positionUs, toKeyframe, trackGroupEnabledStates[i]);
        }
    }

    public void release() {
        if (loader.isLoading()) {
            loader.cancelLoading();
            loader.release();
        }

        if (prepared) {
            // Discard as much as we can synchronously. We only do this if we're prepared, since otherwise
            // sampleQueues may still be being modified by the loading thread.
            for (SampleQueue sampleQueue : sampleQueues) {
                sampleQueue.discardToEnd();
            }

            prepared = false;
            playback = false;
        }

        inReportSink.close();
        samplesSink.close();

        outReportSink.removeListener(session);
        outReportSink.close();

        handler.removeCallbacksAndMessages(null);
        released = true;
    }

    public void selectTracks(TrackSelection[] selections, boolean[] mayRetainStreamFlags,
                             SampleStream[] streams, boolean[] streamResetFlags, long positionUs) {
        Assertions.checkState(prepared);
        // Deselect old tracks.
        for (int i = 0; i < selections.length; i++) {
            if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                setTrackGroupEnabledState(((RtspSampleStream) streams[i]).group, false);
                streams[i] = null;
            }
        }

        // Select new tracks.
        for (int i = 0; i < selections.length; i++) {
            if (streams[i] == null && selections[i] != null) {
                TrackSelection selection = selections[i];
                Assertions.checkState(selection.length() == 1);
                Assertions.checkState(selection.getIndexInTrackGroup(0) == 0);
                int track = trackGroups.indexOf(selection.getTrackGroup());
                Assertions.checkState(!trackGroupEnabledStates[track]);
                enabledTrackCount++;
                trackGroupEnabledStates[track] = true;
                streams[i] = new RtspSampleStream(this, track);
                streamResetFlags[i] = true;
            }
        }

        session.onSelectTracks(sampleQueueTrackTypes, trackGroupEnabledStates);
    }

    /**
     * Enables or disables a specified sample queue.
     *
     * @param sampleQueueIndex The index of the sample queue.
     * @param enabledState True if the sample queue is being enabled, or false if it's being disabled.
     */
    private void setTrackGroupEnabledState(int sampleQueueIndex, boolean enabledState) {
        Assertions.checkState(trackGroupEnabledStates[sampleQueueIndex] != enabledState);
        trackGroupEnabledStates[sampleQueueIndex] = enabledState;
        enabledSampleQueueCount = enabledSampleQueueCount + (enabledState ? 1 : -1);
    }


    // MediaSession.EventListener implementation

    @Override
    public void onPausePlayback() {
    }

    @Override
    public void onResumePlayback() {
        if (pendingResetPositionUs != C.TIME_UNSET) {
            if (loader.isLoading() && playback) {
                loadable.seekLoad();
            }
        }
    }

    @Override
    public void onSeekPlayback() {
        if (pendingResetPositionUs != C.TIME_UNSET) {
            if (loader.isLoading() && playback) {
                loadable.seekLoad();
            }
        }
    }

    @Override
    public void onStopPlayback() {
        if (loader.isLoading() && playback) {
            loadable.cancelLoad();
        }

        release();
    }


    // SequenceableLoader implementation

    @Override
    public boolean continueLoading(long positionUs) {
        if (loadingFinished || !prepared) {
            return false;
        }

        if (loader.isLoading() && !playback) {
            loadCondition.open();
        }

        return true;
    }

    @Override
    public long getNextLoadPositionUs() {
        if (isPendingReset()) {
            return pendingResetPositionUs;
        } else {
            return enabledTrackCount == 0 ? C.TIME_END_OF_SOURCE : getBufferedPositionUs();
        }
    }

    @Override
    public long getBufferedPositionUs() {
        if (loadingFinished) {
            return C.TIME_END_OF_SOURCE;
        } else if (isPendingReset()) {
            return pendingResetPositionUs;
        } else {
            long bufferedPositionUs = Long.MAX_VALUE;

            for (int i = 0; i < sampleQueues.length; i++) {
                if (trackGroupEnabledStates[i]) {
                    bufferedPositionUs = Math.min(bufferedPositionUs,
                            sampleQueues[i].getLargestQueuedTimestampUs());
                }
            }

            return bufferedPositionUs;
        }
    }

    @Override
    public void reevaluateBuffer(long positionUs) {
        // Do nothing.
    }


    // Loader.Callback implementation.

    @Override
    public void onLoadCompleted(MediaStreamLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
        loadingFinished = true;
        pendingResetMediaStreamLoadable = false;
    }

    @Override
    public void onLoadCanceled(MediaStreamLoadable loadable, long elapsedRealtimeMs, long loadDurationMs,
                               boolean released) {
        if (pendingResetMediaStreamLoadable) {
            pendingResetMediaStreamLoadable = false;

        } else {
            loadingFinished = true;
        }
    }

    @Override
    public Loader.LoadErrorAction onLoadError(MediaStreamLoadable loadable, long elapsedRealtimeMs,
                                              long loadDurationMs, IOException error,
                                              int errorCount) {
        loadingFinished = true;
        return Loader.DONT_RETRY;
    }


    // ExtractorOutput implementation. Called by the loading thread.
    @Override
    public SampleQueue track(int id, int type) {
        int trackCount = sampleQueues.length;
        for (int i = 0; i < trackCount; i++) {
            if (sampleQueueTrackIds[i] == id) {
                return sampleQueues[i];
            }
        }

        SampleQueue sampleQueue = new SampleQueue(allocator);
        sampleQueue.setUpstreamFormatChangeListener(this);
        sampleQueueTrackIds = Arrays.copyOf(sampleQueueTrackIds, trackCount + 1);
        sampleQueueTrackIds[trackCount] = id;
        sampleQueueTrackTypes = Arrays.copyOf(sampleQueueTrackTypes, trackCount + 1);
        sampleQueueTrackTypes[trackCount] = type;
        sampleQueues = Arrays.copyOf(sampleQueues, trackCount + 1);
        sampleQueues[trackCount] = sampleQueue;

        trackGroupEnabledStates = Arrays.copyOf(trackGroupEnabledStates, trackCount + 1);
        return sampleQueue;
    }

    @Override
    public void endTracks() {
        sampleQueuesBuilt = true;
        handler.post(maybeFinishPrepareRunnable);
    }

    @Override
    public void seekMap(SeekMap seekMap) {
        // Do nothing.
    }

    // UpstreamFormatChangedListener implementation. Called by the loading thread.
    @Override
    public void onUpstreamFormatChanged(Format format) {
        handler.post(maybeFinishPrepareRunnable);
    }


    // SampleStream implementation.
    public boolean isReady(int trackGroupIndex) {
        return loadingFinished || (sampleQueues[trackGroupIndex].hasNextSample());
    }

    public void maybeThrowError() throws IOException {
        loader.maybeThrowError();
    }

    public int readData(int trackGroupIndex, FormatHolder formatHolder,
                        DecoderInputBuffer buffer, boolean requireFormat) {
        if (isPendingReset()) {
            return C.RESULT_NOTHING_READ;
        }

        return sampleQueues[trackGroupIndex].read(formatHolder, buffer, requireFormat, loadingFinished,
                C.TIME_UNSET);
    }

    public int skipData(int trackGroupIndex, long positionUs) {
        if (isPendingReset()) {
            return 0;
        }

        SampleQueue sampleQueue = sampleQueues[trackGroupIndex];
        if (loadingFinished && positionUs > sampleQueue.getLargestQueuedTimestampUs()) {
            return sampleQueue.advanceToEnd();
        } else {
            return sampleQueue.advanceTo(positionUs, true, true);
        }
    }


    // Internal methods.
    private boolean isPendingReset() {
        return pendingResetPositionUs != C.TIME_UNSET;
    }

    /**
     * Attempts to seek to the specified position within the sample queues.
     *
     * @param positionUs The seek position in microseconds.
     * @return Whether the in-buffer seek was successful.
     */
    private boolean seekInsideBufferUs(long positionUs) {
        for (SampleQueue sampleQueue : sampleQueues) {
            sampleQueue.rewind();
            boolean seekInsideQueue = sampleQueue.advanceTo(positionUs, true, false)
                    != SampleQueue.ADVANCE_FAILED;
            // If we have AV tracks then an in-queue seek is successful if the seek into every AV queue
            // is successful. We ignore whether seeks within non-AV queues are successful in this case, as
            // they may be sparse or poorly interleaved. If we only have non-AV tracks then a seek is
            // successful only if the seek into every queue succeeds.
            if (!seekInsideQueue) {
                return false;
            }
        }
        return true;
    }

    /*private void startLoading() {
        loadable = new MediaStreamLoadable(this, handler, loadCondition);
        loader.startLoading(loadable, this, minLoadableRetryCount);
        prepared = true;
    }*/

    private void startUdpMediaLoader() {
        loadable = new UdpMediaStreamLoadable(this, handler, loadCondition);
        loader.startLoading(loadable, this, minLoadableRetryCount);
        prepared = true;
    }

    private void startTcpMediaLoader() {
        loadable = new TcpMediaStreamLoadable(this, handler, loadCondition);
        loader.startLoading(loadable, this, minLoadableRetryCount);
        prepared = true;
    }

    private void maybeFinishPrepare() {
        if (released || !prepared || playback || !sampleQueuesBuilt) {
            return;
        }

        for (SampleQueue sampleQueue : sampleQueues) {
            if (sampleQueue.getUpstreamFormat() == null) {
                return;
            }
        }

        loadCondition.close();

        trackGroups = buildTrackGroups();
        playback = true;
        listener.onMediaStreamPrepareSuccess(this);
    }

    private void resetSampleQueues() {
        for (SampleQueue sampleQueue : sampleQueues) {
            sampleQueue.reset();
        }
    }

    private TrackGroupArray buildTrackGroups() {
        TrackGroup[] trackGroups = new TrackGroup[sampleQueues.length];

        for (int i = 0; i < sampleQueues.length; i++) {
            trackGroups[i] = new TrackGroup(sampleQueues[i].getUpstreamFormat());
        }

        return new TrackGroupArray(trackGroups);
    }

    /* package */ abstract class MediaStreamLoadable implements Loader.Loadable {
        /**
         * The maximum length of an datagram data packet size, in bytes.
         * 65535 bytes minus IP header (20 bytes) and UDP header (8 bytes)
         */
        public static final int MAX_UDP_PACKET_SIZE = 65507;

        private boolean opened;
        private DataSource dataSource;

        private Extractor extractor;
        private ExtractorInput extractorInput;
        private ExtractorOutput extractorOutput;

        protected volatile boolean loadCanceled;
        private volatile boolean pendingReset;

        private final Handler handler;
        private final ConditionVariable loadCondition;

        public MediaStreamLoadable(ExtractorOutput extractorOutput, Handler handler,
            ConditionVariable loadCondition) {
            this(extractorOutput, handler, loadCondition, false);
        }

        public MediaStreamLoadable(ExtractorOutput extractorOutput, Handler handler,
            ConditionVariable loadCondition, boolean opened) {
            this.extractorOutput = extractorOutput;
            this.loadCondition = loadCondition;
            this.handler = handler;
            this.opened = opened;
        }

        public void seekLoad() {
            pendingReset = true;
        }

        // Loader.Loadable implementation
        @Override
        public void cancelLoad() {
            loadCanceled = true;
        }

        @Override
        public void load() throws IOException, InterruptedException, NullPointerException,
                IllegalStateException {
            try {
                openInternal();

                try {

                    loadMedia();

                } catch (IOException ex) {
                    maybeFinishPlay();
                    throw ex;
                }

            } finally {
                closeInternal();
            }
        }

        // Internal methods
        private void openInternal() throws InterruptedException, IOException {
            try {

                dataSource = buildAndOpenDataSource();

                if (dataSource == null) {
                    maybeFailureOpen();

                } else {

                    MediaFormat format = track.format();
                    Transport transport = format.transport();

                    if (Transport.RTP_PROTOCOL.equals(transport.transportProtocol())) {

                        if (transport.ssrc() != null) {
                            ((RtpDataSinkSource) dataSource).setSsrc(Long.parseLong(transport.ssrc(), 16));
                        }

                        extractorInput = new RtpExtractorInput(dataSource);

                        if (MimeTypes.VIDEO_MP2T.equals(format.format().sampleMimeType())) {
                            extractor = new RtpMp2tExtractor(FLAG_ALLOW_NON_IDR_KEYFRAMES);
                        } else {
                            extractor = new DefaultRtpExtractor(format.format(), trackIdGenerator);
                        }

                    } else {
                        extractorInput = new DefaultExtractorInput(dataSource,
                                0, C.LENGTH_UNSET);

                        if (Transport.MP2T_PROTOCOL.equals(transport.transportProtocol())) {
                            extractor = new TsExtractor(FLAG_ALLOW_NON_IDR_KEYFRAMES);
                        }
                    }

                    if (extractor == null) {
                        if (Transport.RAW_PROTOCOL.equals(transport.transportProtocol())) {
                            extractor = Assertions.checkNotNull(createRawExtractor(extractorInput));
                        }
                    }

                    maybeFinishOpen();

                    if (opened) {
                        loadCondition.block();

                        if (opened) {
                            extractor.init(extractorOutput);
                        }

                    } else {
                        maybeFailureOpen();
                        throw new IOException();
                    }
                }

            } catch (IOException ex) {
                maybeFailureOpen();
                throw ex;
            }
        }

        private Extractor createRawExtractor(ExtractorInput extractorInput)
            throws IOException, InterruptedException {
            Extractor rawExtractor = null;

            for (Extractor extractor : defaultExtractorsFactory.createExtractors()) {
                try {
                    if (extractor.sniff(extractorInput)) {
                        rawExtractor = extractor;
                        break;
                    }
                } catch (EOFException e) {
                    // Do nothing.
                } finally {
                    extractorInput.resetPeekPosition();
                }
            }

            return rawExtractor;
        }

        private void closeInternal() {
            Util.closeQuietly(dataSource);

            opened = false;
            loadCondition.open();
        }

        protected boolean isPendingReset() {
            return pendingReset;
        }

        protected int readInternal(PositionHolder seekPosition) throws IOException, InterruptedException {
            return extractor.read(extractorInput, seekPosition);
        }

        protected void seekInternal(long timeUs) throws IOException, InterruptedException {
            extractor.seek(C.POSITION_UNSET, timeUs);
            pendingReset = false;
        }

        private void maybeFailureOpen() {
            if (loadCanceled || opened) {
                return;
            }

            opened = false;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onMediaStreamPrepareFailure(RtspSampleStreamWrapper.this);
                }
            });
        }

        private void maybeFinishOpen() {
            if (loadCanceled || opened) {
                return;
            }

            opened = true;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onMediaStreamPrepareStarted(RtspSampleStreamWrapper.this);
                }
            });
        }

        protected void maybeFinishPlay() {
            if (loadCanceled || !opened) {
                return;
            }

            opened = false;

            handler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onMediaStreamPlaybackFailure(RtspSampleStreamWrapper.this);
                }
            });
        }

        abstract DataSource buildAndOpenDataSource() throws IOException;
        abstract void loadMedia() throws IOException, InterruptedException;

    }

    /**
     * Loads the media stream and extracts sample data from udp data source.
     */
  /* package */ final class UdpMediaStreamLoadable extends MediaStreamLoadable {
        private UdpDataSinkSource dataSource;

        public UdpMediaStreamLoadable(ExtractorOutput extractorOutput, Handler handler,
                                   ConditionVariable loadCondition) {
            super(extractorOutput, handler, loadCondition);
        }

        // Internal methods
        protected DataSource buildAndOpenDataSource() {
            try {
                MediaFormat format = track.format();
                Transport transport = format.transport();
                boolean isUdpSchema = false;

                if (Transport.RTP_PROTOCOL.equals(transport.transportProtocol())) {
                    @RtpDataSinkSource.Flags int flags = 0;
                    RtpPayloadFormat payloadFormat = format.format();
                    if (session.isRtcpSupported()) {
                        flags |= FLAG_ENABLE_RTCP_FEEDBACK;
                    }

                    if (track.isMuxed()) {
                        flags |= FLAG_FORCE_RTCP_MULTIPLEXING;
                    }

                    dataSource = new RtpDataSinkSource(payloadFormat.clockrate(), flags);

                } else {
                    dataSource = new UdpDataSinkSource(MAX_UDP_PACKET_SIZE);
                    isUdpSchema = true;
                }

                DataSpec dataSpec = new DataSpec(Uri.parse((isUdpSchema ? "udp" : "rtp") + "://" +
                        IPV4_ANY_ADDR + ":0"), DataSpec.FLAG_FORCE_BOUND_LOCAL_ADDRESS);

                dataSource.open(dataSpec);

                localPort = dataSource.getLocalPort();

                return dataSource;

            } catch (IOException e) {
                return null;
            }
        }

        protected void loadMedia() throws IOException, InterruptedException {
            int result = Extractor.RESULT_CONTINUE;
            while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
                while (result == Extractor.RESULT_CONTINUE && !loadCanceled && !isPendingReset()) {
                    try {
                        result = readInternal(null);

                    } catch (IOException e) {
                        if (e.getCause() instanceof SocketTimeoutException) {
                            if (session.getState() == MediaSession.PAUSED) {
                                continue;
                            }
                        }

                        throw e;
                    }
                }

                if (isPendingReset() && pendingResetPositionUs != C.TIME_UNSET) {
                    seekInternal(pendingResetPositionUs);
                    pendingResetPositionUs = C.TIME_UNSET;
                }
            }
        }
    }

    /**
     * Loads the media stream and extracts sample data from tcp data source.
     */
  /* package */ final class TcpMediaStreamLoadable extends MediaStreamLoadable {

        private DataSource dataSource;

        public TcpMediaStreamLoadable(ExtractorOutput extractorOutput, Handler handler,
            ConditionVariable loadCondition) {
            super(extractorOutput, handler, loadCondition, true);
        }

        // Internal methods
        protected DataSource buildAndOpenDataSource() {
            try {
                MediaFormat format = track.format();
                Transport transport = format.transport();

                if (Transport.RTP_PROTOCOL.equals(transport.transportProtocol())) {
                    RtpPayloadFormat payloadFormat = format.format();
                    samplesSink.open(payloadFormat.clockrate());

                    if (session.isRtcpSupported()) {
                        inReportSink.open();
                        outReportSink.open();

                        dataSource = new RtpInternalDataSource(samplesSink, inReportSink,
                                outReportSink);

                    } else {
                        dataSource = new RtpInternalDataSource(samplesSink);
                    }
                }

                DataSpec dataSpec = new DataSpec(Uri.parse(track.url()));
                dataSource.open(dataSpec);

                return dataSource;

            } catch (IOException e) {
                return null;
            }
        }

        protected void loadMedia() throws IOException, InterruptedException {
            int result = Extractor.RESULT_CONTINUE;
            while (result == Extractor.RESULT_CONTINUE && !loadCanceled) {
                while (result == Extractor.RESULT_CONTINUE && !loadCanceled && !isPendingReset()) {
                    result = readInternal(null);
                    // We need to time to avoid cpu overhead.
                    Thread.sleep(10);
                }

                if (isPendingReset() && pendingResetPositionUs != C.TIME_UNSET) {
                    seekInternal(pendingResetPositionUs);
                    pendingResetPositionUs = C.TIME_UNSET;
                }
            }
        }
    }
}
