package androidx.compose.runtime;

/** A Compose state that holds an int: reading it in a composition makes the composition follow it. */
public class SnapshotMutableIntStateImpl implements MutableState<Integer> {
    public SnapshotMutableIntStateImpl(int value) {
    }

    @Override
    public Integer getValue() {
        return 0;
    }

    @Override
    public void setValue(Integer value) {
    }
}
