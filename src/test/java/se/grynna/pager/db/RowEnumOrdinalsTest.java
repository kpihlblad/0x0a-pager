package se.grynna.pager.db;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.junit.jupiter.api.Test;
import se.grynna.pager.splunk.Level;
import se.grynna.pager.splunk.LoadedLogLine;
import se.grynna.pager.splunk.LoadedLogLine.MsgType;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the enum ordinals persisted by {@link LoadedLogLineSerializer}.
 *
 * <p>{@link Level} and {@link MsgType} are stored as {@code ordinal()} bytes, so inserting, reordering or removing a
 * constant silently changes the meaning of rows that are already on disk. When one of these lists changes on purpose,
 * bump {@link RowCodec#FORMAT_VERSION} so old log stores are discarded instead of misread.
 */
class RowEnumOrdinalsTest {

    private static final Kryo ROW_KRYO = newRowKryo();

    @Test
    void levelOrdinalsArePinned() {
        assertThat(Arrays.stream(Level.values()).map(Enum::name))
                .as("Level is persisted by ordinal: bump RowCodec.FORMAT_VERSION if this list changes")
                .containsExactly("ERROR", "WARNING", "INFO");
    }

    @Test
    void msgTypeOrdinalsArePinned() {
        assertThat(Arrays.stream(MsgType.values()).map(Enum::name))
                .as("MsgType is persisted by ordinal: bump RowCodec.FORMAT_VERSION if this list changes")
                .containsExactly("JSON", "RAW");
    }

    @Test
    void everyLevelRoundTripsThroughTheSerializer() {
        for (Level level : Level.values()) {
            LoadedLogLine row = roundTrip(sampleRow(MsgType.JSON, level));

            assertThat(row.getLevel()).as("level %s", level).isEqualTo(level);
            assertThat(row.getMsgType()).isEqualTo(MsgType.JSON);
        }
    }

    @Test
    void everyMsgTypeRoundTripsThroughTheSerializer() {
        for (MsgType msgType : MsgType.values()) {
            LoadedLogLine row = roundTrip(sampleRow(msgType, Level.WARNING));

            assertThat(row.getMsgType()).as("msgType %s", msgType).isEqualTo(msgType);
            assertThat(row.getLevel()).isEqualTo(Level.WARNING);
        }
    }

    @Test
    void nullEnumsRoundTripThroughTheSerializer() {
        LoadedLogLine row = roundTrip(sampleRow(null, null));

        assertThat(row.getMsgType()).isNull();
        assertThat(row.getLevel()).isNull();
        assertThat(row.getMessage()).isEqualTo("message");
        assertThat(row.getSplunkTime()).isEqualTo(Instant.ofEpochSecond(1, 2));
    }

    @Test
    void unknownMsgTypeOrdinalFailsWithIllegalStateException() {
        byte[] bytes = serializedRowWithNullEnums();
        assertRowTailHasNullEnums(bytes);
        int msgTypeOffset = bytes.length - 3;
        bytes[msgTypeOffset] = (byte) 9;

        assertThatThrownBy(() -> read(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown MsgType ordinal 9");
    }

    @Test
    void unknownLevelOrdinalFailsWithIllegalStateException() {
        byte[] bytes = serializedRowWithNullEnums();
        assertRowTailHasNullEnums(bytes);
        int levelOffset = bytes.length - 2;
        bytes[levelOffset] = (byte) 9;

        assertThatThrownBy(() -> read(bytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown Level ordinal 9");
    }

    private static Kryo newRowKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(true);
        kryo.register(LoadedLogLine.class, new LoadedLogLineSerializer());
        return kryo;
    }

    private static LoadedLogLine roundTrip(LoadedLogLine line) {
        byte[] bytes = write(line);
        return read(bytes);
    }

    private static byte[] write(LoadedLogLine line) {
        Output output = new Output(256, -1);
        ROW_KRYO.writeObject(output, line);
        return output.toBytes();
    }

    private static LoadedLogLine read(byte[] bytes) {
        return ROW_KRYO.readObject(new Input(bytes), LoadedLogLine.class);
    }

    private static LoadedLogLine sampleRow(MsgType msgType, Level level) {
        LoadedLogLine line = new LoadedLogLine();
        line.setSegmentId("seg");
        line.setLineNumber(7);
        line.setBkt("bkt");
        line.setCb("cb");
        line.setTime(Instant.ofEpochSecond(1, 2));
        line.setMsgTime(Instant.ofEpochSecond(3, 4));
        line.setIndextime(Instant.ofEpochSecond(5, 6));
        line.setMessage("message");
        line.setContinuation(true);
        line.setRaw("{}");
        line.setEventMap(new TreeMap<>(Map.of("k", "v")));
        line.setMsgType(msgType);
        line.setLevel(level);
        return line;
    }

    /**
     * A row whose enum bytes are both null, so the serialized payload ends with {@code [msgType, level, activity]}.
     * That keeps the two enum bytes addressable from the end without duplicating the row format here.
     */
    private static byte[] serializedRowWithNullEnums() {
        return write(sampleRow(null, null));
    }

    private static void assertRowTailHasNullEnums(byte[] bytes) {
        assertThat(bytes[bytes.length - 3]).as("msgType NULL marker").isEqualTo((byte) -1);
        assertThat(bytes[bytes.length - 2]).as("level NULL marker").isEqualTo((byte) -1);
        assertThat(bytes[bytes.length - 1]).as("no activity").isEqualTo((byte) 0);
    }
}
