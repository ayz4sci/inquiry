package com.afollestad.inquiry;

import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.afollestad.inquiry.annotations.Column;
import com.afollestad.inquiry.annotations.ForeignKey;
import com.afollestad.inquiry.annotations.Table;
import com.afollestad.inquiry.lazyloading.LazyLoaderList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * @author Aidan Follestad (afollestad)
 */
class ClassRowConverter {

    private static byte[] serializeObject(Object obj) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutput out = null;
        try {
            out = new ObjectOutputStream(bos);
            out.writeObject(obj);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize object of type " + obj.getClass().getName(), e);
        } finally {
            try {
                if (out != null)
                    out.close();
            } catch (IOException ignored) {
            }
            try {
                bos.close();
            } catch (IOException ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T deserializeObject(byte[] data, Class<T> cls) {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInput in = null;
        try {
            in = new ObjectInputStream(bis);
            return (T) in.readObject();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deserialize data to type " + cls.getName(), e);
        } finally {
            Utils.closeQuietely(bis);
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String getClassTypeString(Class<?> cls) {
        if (cls.equals(String.class) || cls.equals(char[].class) || cls.equals(Character[].class)) {
            return "TEXT";
        } else if (cls.equals(Float.class) || cls.equals(float.class) ||
                cls.equals(Double.class) || cls.equals(double.class)) {
            return "REAL";
        } else if (cls.equals(Integer.class) || cls.equals(int.class) ||
                cls.equals(Long.class) || cls.equals(long.class) ||
                cls.equals(Boolean.class) || cls.equals(boolean.class)) {
            return "INTEGER";
        } else {
            return "BLOB";
        }
    }

    @Nullable
    private static String getFieldSchema(Field field) {
        ForeignKey fkAnnotation = field.getAnnotation(ForeignKey.class);
        if (fkAnnotation != null)
            return null;

        Column colAnnotation = field.getAnnotation(Column.class);
        if (colAnnotation == null) return null;
        StringBuilder colName = new StringBuilder(selectColumnName(colAnnotation, field));
        colName.append(" ");
        colName.append(getClassTypeString(field.getType()));
        if (colAnnotation.primaryKey())
            colName.append(" PRIMARY KEY");
        if (colAnnotation.autoIncrement())
            colName.append(" AUTOINCREMENT");
        if (colAnnotation.notNull())
            colName.append(" NOT NULL");
        return colName.toString();
    }

    static String getClassTable(Class<?> cls) {
        Table tableAnn = cls.getAnnotation(Table.class);
        if (tableAnn == null)
            throw new IllegalArgumentException("Class " + cls.getName() + " does not use the @Table annotation.");
        String name = tableAnn.name();
        if (name.trim().isEmpty())
            name = cls.getSimpleName().toLowerCase() + "s";
        return name;
    }

    static String getClassSchema(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        List<Field> fields = getAllFields(cls);
        for (Field fld : fields) {
            fld.setAccessible(true);
            final String schema = getFieldSchema(fld);
            if (schema == null) continue;
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(schema);
        }
        if (sb.length() == 0)
            throw new IllegalStateException("Class " + cls.getName() + " has no @Column fields.");
        Log.d("Inquiry", String.format("Scheme for %s: %s", cls.getName(), sb.toString()));
        return sb.toString();
    }

    @DataType.TypeDef
    private static int cursorTypeToColumnType(int cursorType) {
        switch (cursorType) {
            default:
                return DataType.BLOB;
            case Cursor.FIELD_TYPE_FLOAT:
                return DataType.REAL;
            case Cursor.FIELD_TYPE_INTEGER:
                return DataType.INTEGER;
            case Cursor.FIELD_TYPE_STRING:
                return DataType.TEXT;
        }
    }

    /**
     * @param inquiry           An Inquiry instance
     * @param tableName         The name of the child table
     * @param foreignColumnName The name of the column in the child which hooks it ot the parent
     * @param inverseFieldName  The optional name of a field in the child class which gets set to the parent
     * @param row               The DB row
     * @param fieldType         The type of field in the parent class, used to check the return type of this method
     * @param childType         The type of the child class
     * @return object, array, or list depending on the fieldType
     */
    public static Object processForeignKey(@NonNull Inquiry inquiry, @NonNull String tableName, @NonNull String foreignColumnName,
                                           @Nullable String inverseFieldName, @NonNull Object row, @Nullable Class<?> fieldType, @NonNull Class<?> childType) {
        if (fieldType == null)
            fieldType = List.class;

        Field idField = inquiry.getIdField(row.getClass());
        if (idField == null)
            throw new IllegalStateException("You cannot use the @ForeignKey annotation on a field within a class that doesn't have an _id column.");
        Inquiry fkInstance = Inquiry.copy(inquiry, "[@fk]:" + tableName + "//" + foreignColumnName, false);

        long rowId = getRowId(row, idField);
        Object[] vals = fkInstance
                .selectFrom(tableName, childType)
                .where(foreignColumnName + " = ?", rowId)
                .all();

        if (vals == null || vals.length == 0) {
            if (Utils.classImplementsList(fieldType))
                return new ArrayList(0);
            else return null;
        } else {
            if (inverseFieldName != null && !inverseFieldName.isEmpty()) {
                for (Object val : vals) {
                    Field inverseField = getField(getAllFields(childType), inverseFieldName);
                    if (inverseField == null)
                        throw new IllegalStateException("Inverse field " + inverseFieldName + " not found in " + childType);
                    try {
                        inverseField.set(val, row);
                    } catch (Throwable t) {
                        Utils.wrapInReIfNeccessary(t);
                        return null;
                    }
                }
            }

            fkInstance.destroyInstance();
            if (Utils.classImplementsList(fieldType)) {
                List list = new ArrayList(vals.length);
                Collections.addAll(list, vals);
                return list;
            } else if (fieldType.isArray()) {
                return vals;
            } else {
                return vals[0];
            }
        }
    }

    private static void loadFieldIntoRow(Query query, Cursor cursor, Field field, Object row, int columnIndex, @DataType.TypeDef int columnType) throws Exception {
        Class<?> fieldType = field.getType();

        ForeignKey fkAnn = field.getAnnotation(ForeignKey.class);
        if (fkAnn != null) {
            Class<?> childType = Utils.getGenericTypeOfField(field);

            if (Utils.classExtendsLazyLoader(fieldType)) {
                @SuppressWarnings("unchecked")
                LazyLoaderList loader = new LazyLoaderList(query.mInquiry, fkAnn.tableName(), fkAnn.foreignColumnName(),
                        fkAnn.inverseFieldName(), row, childType) {
                    @SuppressWarnings("unchecked")
                    @Override
                    protected void _performLazyLoad() {
                        this.items = (List) processForeignKey(this.inquiry, this.tableName, this.foreignColumnName,
                                this.inverseFieldName, this.row, null, this.childType);
                    }
                };
                field.set(row, loader);
                return;
            }

            Object value = processForeignKey(query.mInquiry, fkAnn.tableName(), fkAnn.foreignColumnName(),
                    fkAnn.inverseFieldName(), row, fieldType, childType);
            field.set(row, value);
            return;
        }

        if (cursor.isNull(columnIndex)) {
            if (fieldType == short.class || fieldType == Short.class ||
                    fieldType == int.class || fieldType == Integer.class ||
                    fieldType == long.class || fieldType == Long.class) {
                field.set(row, 0);
            } else if (fieldType == float.class || fieldType == Float.class) {
                field.set(row, 0f);
            } else if (fieldType == double.class || fieldType == Double.class) {
                field.set(row, 0d);
            } else if (fieldType == boolean.class || fieldType == Boolean.class) {
                field.set(row, false);
            } else {
                field.set(row, null);
            }
            return;
        }

        String columnName = selectColumnName(field.getAnnotation(Column.class), field);
        switch (columnType) {
            case DataType.BLOB:
                byte[] blob = cursor.getBlob(columnIndex);
                if (blob == null)
                    field.set(row, null);
                else if (fieldType == byte.class || fieldType == Byte.class)
                    field.set(row, blob[0]);
                else if (fieldType == byte[].class || fieldType == Byte[].class)
                    field.set(row, blob);
                else if (fieldType == Bitmap.class)
                    field.set(row, BitmapFactory.decodeByteArray(blob, 0, blob.length));
                else
                    field.set(row, deserializeObject(blob, fieldType));
                break;
            case DataType.REAL:
                if (fieldType == short.class || fieldType == Short.class)
                    field.set(row, (short) cursor.getFloat(columnIndex));
                else if (fieldType == int.class || fieldType == Integer.class)
                    field.set(row, (int) cursor.getFloat(columnIndex));
                else if (fieldType == long.class || fieldType == Long.class)
                    field.set(row, (long) cursor.getFloat(columnIndex));
                else if (fieldType == float.class || fieldType == Float.class)
                    field.set(row, cursor.getFloat(columnIndex));
                else if (fieldType == double.class || fieldType == Double.class)
                    field.set(row, cursor.getDouble(columnIndex));
                else
                    throw new IllegalStateException(String.format("Column %s of type REAL (float/double) doesn't match field of type %s",
                            columnName, fieldType.getName()));
                break;
            case DataType.INTEGER:
                if (fieldType == short.class || fieldType == Short.class)
                    field.set(row, cursor.getShort(columnIndex));
                else if (fieldType == int.class || fieldType == Integer.class)
                    field.set(row, cursor.getInt(columnIndex));
                else if (fieldType == long.class || fieldType == Long.class)
                    field.set(row, cursor.getLong(columnIndex));
                else if (fieldType == boolean.class || fieldType == Boolean.class)
                    field.set(row, cursor.getInt(columnIndex) == 1);
                else if (fieldType == float.class || fieldType == Float.class)
                    field.set(row, (float) cursor.getInt(columnIndex));
                else if (fieldType == double.class || fieldType == Double.class)
                    field.set(row, (double) cursor.getInt(columnIndex));
                else
                    throw new IllegalStateException(String.format("Column %s of type INTEGER (decimal) doesn't match field of type %s",
                            columnName, fieldType.getName()));
                break;
            case DataType.TEXT:
                String text = cursor.getString(columnIndex);
                if (fieldType == String.class || fieldType == CharSequence.class)
                    field.set(row, text);
                else if (fieldType == char[].class || fieldType == Character[].class)
                    field.set(row, text != null && text.length() > 0 ? text.toCharArray() : null);
                else if (fieldType == char.class || fieldType == Character.class)
                    field.set(row, text != null && text.length() > 0 ? text.charAt(0) : null);
                else
                    throw new IllegalStateException(String.format("Column %s of type TEXT (string) doesn't match field of type %s",
                            columnName, fieldType.getName()));
                break;
        }
    }

    /**
     * Avoids the need to loop over fields for every column by caching them ahead of time.
     */
    private static HashMap<String, Field> buildFieldCache(Class<?> cls, List<Field> outFkFields) {
        HashMap<String, Field> cache = new HashMap<>();
        List<Field> fields = getAllFields(cls);
        for (Field fld : fields) {
            ForeignKey fkAnn = fld.getAnnotation(ForeignKey.class);
            if (fkAnn != null) {
                outFkFields.add(fld);
                continue;
            }

            Column colAnn = fld.getAnnotation(Column.class);
            String name = selectColumnName(colAnn, fld);
            cache.put(name, fld);
        }
        return cache;
    }

    static <T> T cursorToCls(Query query, Cursor cursor, Class<T> cls) {
        T row = Utils.newInstance(cls);
        List<Field> fkFields = new ArrayList<>(2);
        HashMap<String, Field> fieldCache = buildFieldCache(cls, fkFields);

        int columnIndex;
        for (columnIndex = 0; columnIndex < cursor.getColumnCount(); columnIndex++) {
            String columnName = cursor.getColumnName(columnIndex);
            int columnType = cursorTypeToColumnType(cursor.getType(columnIndex));
            try {
                final Field columnField = fieldCache.get(columnName);
                columnField.setAccessible(true);
                loadFieldIntoRow(query, cursor, columnField, row, columnIndex, columnType);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(String.format("No field found in %s for column %s (of type %s)",
                        cls.getName(), columnName, DataType.name(columnType)));
            } catch (Exception e) {
                Utils.wrapInReIfNeccessary(e);
            }
        }

        if (fkFields.size() > 0) {
            for (Field fld : fkFields) {
                fld.setAccessible(true);
                try {
                    //noinspection WrongConstant
                    loadFieldIntoRow(query, cursor, fld, row, columnIndex, -1);
                } catch (Throwable t) {
                    Utils.wrapInReIfNeccessary(t);
                }
            }
        }

        return row;
    }

    @Nullable
    static Field getField(@Nullable List<Field> fields, @NonNull String name, @Nullable Class<?>... requiredTypes) {
        if (fields == null || fields.size() == 0) return null;
        for (Field fld : fields) {
            fld.setAccessible(true);
            if (requiredTypes != null && requiredTypes.length > 0) {
                boolean matchesOneRequiredType = false;
                for (Class<?> cls : requiredTypes) {
                    if (fld.getType() == cls) {
                        matchesOneRequiredType = true;
                        break;
                    }
                }
                if (!matchesOneRequiredType) continue;
            }
            Column colAnn = fld.getAnnotation(Column.class);
            String colName = selectColumnName(colAnn, fld);
            if (colName.equals(name))
                return fld;
        }
        return null;
    }

    static long getRowId(@NonNull Object val, @Nullable Field idField) {
        if (idField != null) {
            try {
                return idField.getLong(val);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to get the value of the _id field of a row.", e);
            }
        }
        return -1;
    }

    static void setIdField(@NonNull Object val, @Nullable Field idField, long id) {
        if (idField != null) {
            try {
                idField.setLong(val, id);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to set _id field of a row.", e);
            }
        }
    }

    static String[] generateProjection(Class<?> cls) {
        ArrayList<String> projection = new ArrayList<>();
        List<Field> fields = getAllFields(cls);
        for (Field fld : fields) {
            fld.setAccessible(true);
            ForeignKey refAnn = fld.getAnnotation(ForeignKey.class);
            if (refAnn != null)
                continue;
            Column colAnn = fld.getAnnotation(Column.class);
            if (colAnn == null) continue;
            projection.add(selectColumnName(colAnn, fld));
        }
        return projection.toArray(new String[projection.size()]);
    }

    static String[] generateDistinct(String[] distinct) {
        ArrayList<String> projectionArrayList = new ArrayList<>();
        String distinctQuery = "DISTINCT " + TextUtils.join(",", distinct);
        projectionArrayList.add(0, distinctQuery);
        return projectionArrayList.toArray(new String[projectionArrayList.size()]);
    }

    static String selectColumnName(Column ann, Field fld) {
        if (ann == null)
            ann = fld.getAnnotation(Column.class);
        if (ann == null) return fld.getName();
        if (!ann.name().trim().isEmpty())
            return ann.name();
        return fld.getName();
    }

    static List<Field> getAllFields(Class cls) {
        List<Field> fields = new ArrayList<>();
        while (true) {
            Collections.addAll(fields, cls.getDeclaredFields());
            cls = cls.getSuperclass();
            if (cls == null) {
                break;
            }
        }
        return fields;
    }

    static ContentValues clsToVals(@NonNull Query<?, ?> query, @NonNull Object row, @Nullable String[] projection, @Nullable List<Field> fields, boolean updateMode) {
        try {
            ContentValues vals = new ContentValues();
            if (fields == null)
                fields = getAllFields(row.getClass());

            int columnCount = 0;
            for (Field fld : fields) {
                fld.setAccessible(true);
                if (projection != null && projection.length > 0) {
                    boolean skip = true;
                    for (String proj : projection) {
                        if (proj != null && proj.equalsIgnoreCase(selectColumnName(null, fld))) {
                            skip = false;
                            break;
                        }
                    }
                    if (skip) continue;
                }

                ForeignKey fkAnn = fld.getAnnotation(ForeignKey.class);
                if (fkAnn != null) {
                    if (query.mForeignChildren == null)
                        query.mForeignChildren = new HashMap<>();
                    query.mForeignChildren.put(row, fld);
                    continue;
                }

                Column colAnn = fld.getAnnotation(Column.class);
                if (colAnn == null) continue;
                columnCount++;
                Class<?> fldType = fld.getType();
                Object fldVal = fld.get(row);
                if (colAnn.autoIncrement() && (Long) fldVal <= 0) continue;
                if (fldVal == null) continue;

                final String columnName = selectColumnName(colAnn, fld);
                if (fldType.equals(String.class)) {
                    vals.put(columnName, (String) fldVal);
                } else if (fldType.equals(char[].class) || fldType.equals(Character[].class)) {
                    vals.put(columnName, new String((char[]) fldVal));
                } else if (fldType.equals(Float.class) || fldType.equals(float.class)) {
                    vals.put(columnName, (float) fldVal);
                } else if (fldType.equals(Double.class) || fldType.equals(double.class)) {
                    vals.put(columnName, (double) fldVal);
                } else if (fldType.equals(Short.class) || fldType.equals(short.class)) {
                    vals.put(columnName, (short) fldVal);
                } else if (fldType.equals(Integer.class) || fldType.equals(int.class)) {
                    vals.put(columnName, (int) fldVal);
                } else if (fldType.equals(Long.class) || fldType.equals(long.class)) {
                    vals.put(columnName, (long) fldVal);
                } else if (fldType.equals(char.class) || fldType.equals(Character.class)) {
                    vals.put(columnName, Character.toString((char) fldVal));
                } else if (fldType.equals(Boolean.class) || fldType.equals(boolean.class)) {
                    vals.put(columnName, ((boolean) fldVal) ? 1 : 0);
                } else if (fldType.equals(Bitmap.class)) {
                    vals.put(columnName, bitmapToBytes((Bitmap) fldVal));
                } else if (fldType.equals(Byte.class) || fldType.equals(byte.class)) {
                    vals.put(columnName, (byte) fldVal);
                } else if (fldType.equals(Byte[].class) || fldType.equals(byte[].class)) {
                    vals.put(columnName, (byte[]) fldVal);
                } else if (fldVal instanceof Serializable) {
                    vals.put(columnName, serializeObject(fldVal));
                } else {
                    throw new IllegalStateException(String.format("Class %s should be marked as Serializable, or field %s should use the @ForeignKey annotation instead of @Column.",
                            fldType.getName(), fld.getName()));
                }
            }

            if (columnCount == 0)
                throw new IllegalStateException("Class " + row.getClass().getName() + " has no column fields.");
            return vals;
        } catch (Throwable t) {
            Utils.wrapInReIfNeccessary(t);
            return null;
        }
    }

    private static byte[] bitmapToBytes(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            return stream.toByteArray();
        } finally {
            Utils.closeQuietely(stream);
        }
    }
}