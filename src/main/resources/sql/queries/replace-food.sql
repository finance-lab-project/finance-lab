UPDATE category_month
   SET actual = :amount,
       revision = CASE WHEN actual = :amount THEN revision ELSE revision + 1 END
 WHERE owner = :owner AND period = :period AND category = 'food' AND revision = :revision;
