package dev.liambloom.checker.book;

import java.util.prefs.Preferences;

public abstract class AbstractModifiableBook extends AbstractBook implements ModifiableBook {
    public AbstractModifiableBook(String name) {
        super(name);
    }

    @Override
    public void rename(String name) {
        super.rename(name);
    }

    /*@Override
    public void rename(String name) {
        if (name.length() > Preferences.MAX_KEY_LENGTH)
            throw new IllegalArgumentException("Test names may not be longer than " + Preferences.MAX_KEY_LENGTH + " characters long");
        Preferences index = Books.getCustomTests().node("index");
        int size = index.getInt("size", 0);
        String oldName = getName();
        for (int i = 0; i < size; i++) {
            if (index.get(Integer.toString(i), null).equals(oldName)) {
                index.put(Integer.toString(i), name);
                Books.getCustomTests().put(name, Books.getCustomTests().get(oldName, null));
                Books.getCustomTests().remove(oldName);
                setName(name);
                Books.updateLoadedBookName(oldName);
                return;
            }
        }
        throw new IllegalStateException("Book not found in index");
    }*/
}
