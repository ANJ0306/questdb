/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2019 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package io.questdb.std.str;

import io.questdb.std.ThreadLocal;

public final class SingleCharCharSequence extends AbstractCharSequence {

    private static final ThreadLocal<SingleCharCharSequence> TL_CHAR_SEQUENCE = new ThreadLocal<>(SingleCharCharSequence::new);
    private char value;

    public static CharSequence get(char value) {
        final SingleCharCharSequence that = TL_CHAR_SEQUENCE.get();
        that.value = value;
        return that;
    }

    @Override
    public int length() {
        return 1;
    }

    @Override
    public char charAt(int index) {
        return value;
    }
}
