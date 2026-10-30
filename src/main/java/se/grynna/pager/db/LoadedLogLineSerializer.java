package se.grynna.pager.db;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import se.grynna.pager.splunk.Activity;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Explicit Kryo serializer for {@link LoadedLogLine} rows.
 *
 * <p>The field order is fixed here rather than derived by reflection. This keeps
 * the stored format stable and makes every field, including the enums and the
 * {@link Activity} record, part of an intentional schema. The row format is
 * versioned by {@link RowCodec#FORMAT_VERSION}; a stored format that does not
 * match is discarded (only the log store, never the config store).
 */
final class LoadedLogLineSerializer extends Serializer<LoadedLogLine> {

    private static final int NULL = -1;

    LoadedLogLineSerializer() {
        super(false);
    }

    @Override
    public void write(Kryo kryo, Output out, LoadedLogLine line) {
        out.writeString(line.getSegmentId());
        out.writeInt(line.line());
        out.writeString(line.getBkt());
        out.writeString(line.getCb());
        writeInstant(out, line.getSplunkTime());
        writeInstant(out, line.getDisplayTime());
        writeInstant(out, line.getIndextime());
        out.writeString(line.getMessage());
        out.writeBoolean(line.isContinuation());
        out.writeString(line.getRaw());
        writeEventMap(out, line.getEventMap());
        out.writeByte(ordinalOrNull(line.getMsgType()));
        out.writeByte(ordinalOrNull(line.getLevel()));
        writeActivity(out, line.getActivity());
    }

    @Override
    public LoadedLogLine read(Kryo kryo, Input in, Class<? extends LoadedLogLine> type) {
        LoadedLogLine line = new LoadedLogLine();
        line.setSegmentId(in.readString());
        line.setLineNumber(in.readInt());
        line.setBkt(in.readString());
        line.setCb(in.readString());
        line.setTime(readInstant(in));
        line.setMsgTime(readInstant(in));
        line.setIndextime(readInstant(in));
        line.setMessage(in.readString());
        line.setContinuation(in.readBoolean());
        line.setRaw(in.readString());
        line.setEventMap(readEventMap(in));
        line.setMsgType(msgType(in.readByte()));
        line.setLevel(level(in.readByte()));
        line.setActivity(readActivity(in));
        return line;
    }

    private static void writeInstant(Output out, Instant instant) {
        out.writeBoolean(instant != null);
        if (instant != null) {
            // Keep full nanosecond precision: epoch millis would lose it.
            out.writeLong(instant.getEpochSecond());
            out.writeInt(instant.getNano());
        }
    }

    private static Instant readInstant(Input in) {
        if (!in.readBoolean()) {
            return null;
        }
        return Instant.ofEpochSecond(in.readLong(), in.readInt());
    }

    private static void writeEventMap(Output out, Map<String, String> eventMap) {
        if (eventMap == null) {
            out.writeInt(NULL);
            return;
        }
        out.writeInt(eventMap.size());
        for (Map.Entry<String, String> entry : eventMap.entrySet()) {
            out.writeString(entry.getKey());
            out.writeString(entry.getValue());
        }
    }

    private static TreeMap<String, String> readEventMap(Input in) {
        int size = in.readInt();
        if (size == NULL) {
            return null;
        }
        TreeMap<String, String> eventMap = new TreeMap<>();
        for (int i = 0; i < size; i++) {
            eventMap.put(in.readString(), in.readString());
        }
        return eventMap;
    }

    private static void writeActivity(Output out, Activity activity) {
        out.writeBoolean(activity != null);
        if (activity != null) {
            out.writeString(activity.machine());
            out.writeString(activity.process());
            out.writeString(activity.thread());
            out.writeString(activity.request());
            out.writeString(activity.origin());
            out.writeString(activity.processTerm());
        }
    }

    private static Activity readActivity(Input in) {
        if (!in.readBoolean()) {
            return null;
        }
        return new Activity(in.readString(), in.readString(), in.readString(), in.readString(), in.readString(),
                in.readString());
    }

    private static int ordinalOrNull(Enum<?> value) {
        return value == null ? NULL : value.ordinal();
    }

    private static MsgType msgType(byte ordinal) {
        if (ordinal == NULL) {
            return null;
        }
        MsgType[] values = MsgType.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalStateException("Unknown MsgType ordinal " + ordinal + " in log store row");
        }
        return values[ordinal];
    }

    private static Level level(byte ordinal) {
        if (ordinal == NULL) {
            return null;
        }
        Level[] values = Level.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalStateException("Unknown Level ordinal " + ordinal + " in log store row");
        }
        return values[ordinal];
    }
}
