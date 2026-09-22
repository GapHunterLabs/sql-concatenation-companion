def orders_for(cursor, customer):
    # Real risk: f-string with a variable, executed -- SHOULD be flagged.
    cursor.execute(f"SELECT * FROM orders WHERE customer = '{customer}'")
    return cursor.fetchall()


def order_count(cursor, order_id):
    # A %d placeholder only accepts numbers -- should NOT be flagged.
    cursor.execute("SELECT count(*) FROM items WHERE order_id = %d" % order_id)
    return cursor.fetchone()


def orders_safe(cursor, customer):
    # Parameterized -- should NOT be flagged.
    cursor.execute("SELECT * FROM orders WHERE customer = %s", (customer,))
    return cursor.fetchall()
