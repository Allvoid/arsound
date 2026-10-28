package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;

import org.junit.Test;

public class ClassNamesTest {
    @Test
    public void theClassItselfCounts() {
        assertTrue(ClassNames.hasParentNamed(ArrayList.class, "ArrayList"));
    }

    @Test
    public void aParentFurtherUpCounts() {
        assertTrue(ClassNames.hasParentNamed(ArrayList.class, "AbstractList"));
        assertTrue(ClassNames.hasParentNamed(ArrayList.class, "Object"));
    }

    @Test
    public void anyOfTheNamesIsEnough() {
        assertTrue(ClassNames.hasParentNamed(ArrayList.class, "RecyclerView", "AbstractList"));
    }

    @Test
    public void aChildOfTheNamedClassIsNotIt() {
        assertFalse(ClassNames.hasParentNamed(AbstractList.class, "ArrayList"));
    }

    @Test
    public void thePackageIsNotPartOfTheName() {
        assertFalse(ClassNames.hasParentNamed(ArrayList.class, "java.util.ArrayList"));
    }

    @Test
    public void aSimilarNameIsNotTheName() {
        assertFalse(ClassNames.hasParentNamed(ArrayList.class, "List", "Array", "MyArrayList"));
    }
}
