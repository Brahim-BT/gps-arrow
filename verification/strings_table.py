# -*- coding: utf-8 -*-
"""One aligned table -> three strings.xml files. Keeps the languages key-for-key identical."""

# (key, en, fr, ar)
STRINGS = [
    ("SECTION", "Product name and tabs", None, None),
    ("app_name", "GPS Baibbat", "GPS Baibbat", "GPS Baibbat"),
    # Referenced from AndroidManifest.xml for the geo: intent filter's activity label.
    ("navigate_here", "Navigate here", "Naviguer jusqu\u2019ici", "\u0627\u0644\u062a\u0648\u062c\u0647 \u0625\u0644\u0649 \u0647\u0646\u0627"),
    ("app_subtitle", "NAVIGATOR", "NAVIGATEUR", "الملاح"),
    ("tab_arrow", "Arrow", "Flèche", "السهم"),
    ("tab_destinations", "Destinations", "Destinations", "الوجهات"),
    ("tab_map", "Map", "Carte", "الخريطة"),
    ("tab_settings", "Settings", "Réglages", "الإعدادات"),

    ("SECTION", "Arrow screen", None, None),
    ("save_my_location", "Save my location", "Enregistrer ma position", "حفظ موقعي"),
    ("save_my_location_no_fix", "Save my location (no fix yet)",
     "Enregistrer ma position (position non acquise)", "حفظ موقعي (لم يُحدَّد الموقع بعد)"),
    ("save_my_location_stale", "Save my location (fix is stale)",
     "Enregistrer ma position (position périmée)", "حفظ موقعي (تحديد الموقع قديم)"),
    ("arrived_title", "You’re here", "Vous y êtes", "أنت هنا"),
    ("arrived_explanation", "Closer than this fix can measure, so the needle is showing north.",
     "Plus près que ce que cette position peut mesurer : l’aiguille indique le nord.",
     "أقرب مما يمكن لتحديد الموقع هذا قياسه، لذلك يشير المؤشر إلى الشمال."),
    ("pointing_north_no_destination", "Pointing north — no destination chosen yet",
     "Pointe vers le nord — aucune destination choisie",
     "يشير إلى الشمال — لم تُختَر وجهة بعد"),
    ("pointing_north_waiting_fix", "Pointing north — waiting for a position fix",
     "Pointe vers le nord — en attente de la position",
     "يشير إلى الشمال — في انتظار تحديد الموقع"),
    ("magnetic_north_notice",
     "That’s magnetic north. It becomes true north once there’s a position fix to compute the declination from.",
     "C’est le nord magnétique. Il deviendra le nord géographique dès qu’une position permettra de calculer la déclinaison.",
     "هذا هو الشمال المغناطيسي. سيصبح الشمال الجغرافي بمجرد توفر تحديد للموقع لحساب الانحراف المغناطيسي."),
    ("tap_destinations_hint", "Tap Destinations to pick one.",
     "Touchez Destinations pour en choisir une.", "اضغط على «الوجهات» لاختيار واحدة."),
    ("compass_unreliable", "Compass unreliable — wave the phone in a figure of eight to recalibrate.",
     "Boussole peu fiable — décrivez un 8 avec le téléphone pour la recalibrer.",
     "البوصلة غير موثوقة — حرّك الهاتف على شكل الرقم 8 لإعادة معايرتها."),
    ("position_out_of_date", "Position is out of date — searching for satellites.",
     "Position périmée — recherche de satellites en cours.",
     "الموقع قديم — جارٍ البحث عن الأقمار الصناعية."),
    ("no_compass_title", "No compass", "Pas de boussole", "لا توجد بوصلة"),
    ("no_compass_body",
     "This device has no usable compass sensor. The arrow will work once you start moving, using your GPS course instead.",
     "Cet appareil n’a pas de capteur de boussole utilisable. La flèche fonctionnera dès que vous commencerez à vous déplacer, en utilisant votre cap GPS.",
     "لا يحتوي هذا الجهاز على مستشعر بوصلة صالح. سيعمل السهم بمجرد أن تبدأ بالتحرك، مستخدماً اتجاه سيرك من GPS."),

    ("SECTION", "Arrow screen corner readouts", None, None),
    ("label_destination", "Destination", "Destination", "الوجهة"),
    ("label_distance", "Distance", "Distance", "المسافة"),
    ("label_speed", "Speed", "Vitesse", "السرعة"),
    ("label_direction", "Direction", "Direction", "الاتجاه"),
    ("no_destination_yet", "None chosen", "Aucune choisie", "لم تُختَر"),
    ("value_unknown", "—", "—", "—"),
    ("unit_kmh", "%1$s km/h", "%1$s km/h", "%1$s كم/س"),
    ("unit_mph", "%1$s mph", "%1$s mph", "%1$s ميل/س"),
    ("unit_knots", "%1$s kn", "%1$s nd", "%1$s عقدة"),

    ("SECTION", "The user\u2019s own position", None, None),
    ("my_position", "My position", "Ma position", "موقعي"),
    ("position_none", "No position yet", "Position non acquise", "لم يُحدَّد الموقع بعد"),
    ("position_stale", "This is where you were, not where you are",
     "C\u2019est l\u00e0 o\u00f9 vous \u00e9tiez, pas l\u00e0 o\u00f9 vous \u00eates",
     "\u0647\u0630\u0627 \u0645\u0643\u0627\u0646\u0643 \u0627\u0644\u0633\u0627\u0628\u0642 \u0644\u0627 \u0645\u0643\u0627\u0646\u0643 \u0627\u0644\u062d\u0627\u0644\u064a"),
    ("position_copied", "Position copied", "Position copi\u00e9e", "\u062a\u0645 \u0646\u0633\u062e \u0627\u0644\u0645\u0648\u0642\u0639"),
    ("cd_copy_position", "Copy my position", "Copier ma position", "\u0646\u0633\u062e \u0645\u0648\u0642\u0639\u064a"),
    ("coord_format_decimal", "Decimal", "D\u00e9cimal", "\u0639\u0634\u0631\u064a"),
    ("coord_format_dms", "DMS", "DMS", "\u062f\u0631\u062c\u0627\u062a"),
    ("coord_format_plus_code", "Plus code", "Plus code", "Plus code"),
    ("coord_format_mgrs", "MGRS", "MGRS", "MGRS"),

    ("SECTION", "Location availability \u2014 three distinct problems, three remedies", None, None),
    ("location_off_title", "Location is switched off",
     "La localisation est d\u00e9sactiv\u00e9e",
     "\u062e\u062f\u0645\u0629 \u0627\u0644\u0645\u0648\u0642\u0639 \u0645\u0639\u0637\u0651\u0644\u0629"),
    ("location_off_body",
     "You gave this app permission, but the device\u2019s location switch is off, so no app can get a position. The arrow cannot work until you turn it on.",
     "Vous avez autoris\u00e9 cette application, mais la localisation de l\u2019appareil est d\u00e9sactiv\u00e9e : aucune application ne peut obtenir de position. La fl\u00e8che ne fonctionnera pas tant qu\u2019elle n\u2019est pas activ\u00e9e.",
     "\u0644\u0642\u062f \u0645\u0646\u062d\u062a \u0627\u0644\u0625\u0630\u0646 \u0644\u0647\u0630\u0627 \u0627\u0644\u062a\u0637\u0628\u064a\u0642\u060c \u0644\u0643\u0646 \u0645\u0641\u062a\u0627\u062d \u0627\u0644\u0645\u0648\u0642\u0639 \u0641\u064a \u0627\u0644\u062c\u0647\u0627\u0632 \u0645\u0637\u0641\u0623\u060c \u0641\u0644\u0627 \u064a\u0645\u0643\u0646 \u0644\u0623\u064a \u062a\u0637\u0628\u064a\u0642 \u0623\u0646 \u064a\u062d\u062f\u062f \u0627\u0644\u0645\u0648\u0642\u0639. \u0644\u0646 \u064a\u0639\u0645\u0644 \u0627\u0644\u0633\u0647\u0645 \u062d\u062a\u0649 \u062a\u0641\u0639\u0651\u0644\u0647."),
    ("location_off_button", "Turn on location", "Activer la localisation",
     "\u062a\u0641\u0639\u064a\u0644 \u0627\u0644\u0645\u0648\u0642\u0639"),
    ("acquiring_title", "Searching for satellites", "Recherche de satellites",
     "\u062c\u0627\u0631\u064d \u0627\u0644\u0628\u062d\u062b \u0639\u0646 \u0627\u0644\u0623\u0642\u0645\u0627\u0631 \u0627\u0644\u0635\u0646\u0627\u0639\u064a\u0629"),
    ("acquiring_body",
     "%1$s of %2$s satellites in view. With no internet this takes 30 to 90 seconds from cold, sometimes longer. Go outside or stand where you can see sky.",
     "%1$s satellites sur %2$s en vue. Sans Internet, cela prend de 30 \u00e0 90 secondes \u00e0 froid, parfois davantage. Sortez ou placez-vous l\u00e0 o\u00f9 vous voyez le ciel.",
     "%1$s \u0645\u0646 %2$s \u0642\u0645\u0631\u064b\u0627 \u0641\u064a \u0627\u0644\u0645\u062f\u0649. \u0628\u062f\u0648\u0646 \u0625\u0646\u062a\u0631\u0646\u062a \u064a\u0633\u062a\u063a\u0631\u0642 \u0630\u0644\u0643 \u0645\u0646 30 \u0625\u0644\u0649 90 \u062b\u0627\u0646\u064a\u0629 \u0639\u0646\u062f \u0627\u0644\u0628\u062f\u0621\u060c \u0648\u0631\u0628\u0645\u0627 \u0623\u0643\u062b\u0631. \u0627\u062e\u0631\u062c \u0625\u0644\u0649 \u0627\u0644\u0639\u0631\u0627\u0621 \u0623\u0648 \u0642\u0641 \u062d\u064a\u062b \u062a\u0631\u0649 \u0627\u0644\u0633\u0645\u0627\u0621."),

    ("SECTION", "Status chips", None, None),
    ("chip_accuracy", "±%1$s m", "±%1$s m", "±%1$s م"),
    ("chip_accuracy_weak", "weak ±%1$s m", "faible ±%1$s m", "ضعيف ±%1$s م"),
    ("chip_stale_fix", "stale fix", "position périmée", "تحديد قديم"),
    ("chip_no_fix", "no fix", "pas de position", "لا يوجد تحديد"),
    ("chip_satellites", "%1$s/%2$s sats", "%1$s/%2$s sat.", "%1$s/%2$s قمر"),

    ("SECTION", "Heading source", None, None),
    ("heading_compass", "Compass", "Boussole", "بوصلة"),
    ("heading_compass_magnetic", "Compass (magnetic)", "Boussole (magnétique)", "بوصلة (مغناطيسية)"),
    ("heading_gps_course", "GPS course", "Cap GPS", "اتجاه GPS"),
    ("heading_compass_uncalibrated", "Compass needs calibration", "Boussole à calibrer",
     "البوصلة تحتاج إلى معايرة"),
    ("heading_none", "No heading", "Pas de cap", "لا يوجد اتجاه"),

    ("SECTION", "Units and bearings", None, None),
    ("unit_metres", "%1$s m", "%1$s m", "%1$s م"),
    ("unit_kilometres", "%1$s km", "%1$s km", "%1$s كم"),
    ("unit_feet", "%1$s ft", "%1$s pi", "%1$s قدم"),
    ("unit_miles", "%1$s mi", "%1$s mi", "%1$s ميل"),
    ("unit_nautical_miles", "%1$s NM", "%1$s NM", "%1$s ميل بحري"),
    ("distance_under", "under %1$s", "moins de %1$s", "أقل من %1$s"),
    ("bearing_format", "%1$s° %2$s", "%1$s° %2$s", "%1$s° %2$s"),

    ("SECTION", "Destinations list", None, None),
    ("destinations_title", "Destinations", "Destinations", "الوجهات"),
    ("select_destination", "Select destination", "Choisissez une destination", "اختر وجهة"),
    ("search", "Search", "Rechercher", "بحث"),
    ("search_placeholder", "Name or note", "Nom ou note", "الاسم أو الملاحظة"),
    ("sort_needs_position", "needs a position fix", "nécessite une position",
     "يتطلب تحديد الموقع"),
    ("filter_starred", "Starred", "Favoris", "المفضّلة"),
    ("distances_stale_warning", "Distances are from your last known position, not a live fix.",
     "Les distances sont calculées depuis votre dernière position connue, pas depuis une position actuelle.",
     "المسافات محسوبة من آخر موقع معروف، وليس من تحديد حالي للموقع."),
    ("empty_no_destinations_title", "No destinations yet", "Aucune destination",
     "لا توجد وجهات بعد"),
    ("empty_no_destinations_body",
     "Save your location from the arrow screen, or add a point from coordinates, a plus code or an MGRS reference.",
     "Enregistrez votre position depuis l’écran de la flèche, ou ajoutez un point à partir de coordonnées, d’un plus code ou d’une référence MGRS.",
     "احفظ موقعك من شاشة السهم، أو أضف نقطة من الإحداثيات أو من plus code أو من مرجع MGRS."),
    ("empty_add_first", "Add your first one", "Ajouter la première", "أضف أول وجهة"),
    ("empty_nothing_matches_title", "Nothing matches", "Aucun résultat", "لا توجد نتائج"),
    ("empty_no_starred_match", "No starred point matches “%1$s”.",
     "Aucun point favori ne correspond à « %1$s ».", "لا توجد نقطة مفضّلة تطابق «%1$s»."),
    ("empty_no_starred",
     "You haven’t starred any points yet. Tap the star on a row to add one.",
     "Vous n’avez encore aucun favori. Touchez l’étoile sur une ligne pour en ajouter un.",
     "لم تضِف أي نقطة إلى المفضّلة بعد. اضغط على النجمة في أي صف لإضافة واحدة."),
    ("empty_no_match", "No saved point matches “%1$s”.",
     "Aucun point enregistré ne correspond à « %1$s ».", "لا توجد نقطة محفوظة تطابق «%1$s»."),
    ("clear_search", "Clear search", "Effacer la recherche", "مسح البحث"),
    ("cd_add_destination", "Add a destination", "Ajouter une destination", "إضافة وجهة"),
    ("cd_star", "Star %1$s", "Ajouter %1$s aux favoris", "إضافة %1$s إلى المفضّلة"),
    ("cd_unstar", "Unstar %1$s", "Retirer %1$s des favoris", "إزالة %1$s من المفضّلة"),
    ("cd_edit", "Edit %1$s", "Modifier %1$s", "تعديل %1$s"),
    ("cd_delete", "Delete %1$s", "Supprimer %1$s", "حذف %1$s"),
    # What the app is allowed to say about one of the user's own points being public.
    #
    # Exactly one of these four is a claim about the world — shared_badge, reached only by having
    # seen the point in a fetched feed. The other three name the uncertainty instead of resolving
    # it, and the reason they exist is the row where a permanently-offline user taps the switch
    # off: the app must not report a withdrawal it has no way to have observed.
    #
    # None of them forecasts. There was a version that said the point would be gone "within about
    # a day", which is a prediction about whether a scheduled job ran, presented to the user as a
    # fact about their camp. Observed state only.
    ("shared_badge", "Publicly shared", "Partagée publiquement", "منشورة للعموم"),
    # Public, and the public copy is not what this device holds. Both halves are load-bearing:
    # drop "publicly shared" and the user stops knowing the point is out there at all; drop the
    # second clause and a correction they have already made looks delivered when it is not.
    # One string rather than two, so a stale note and a stale coordinate read the same — the
    # table is worth more for being small enough to check by reading.
    ("shared_edit_unpublished", "Publicly shared — your edit is not published yet",
     "Partagée publiquement — votre modification n’est pas encore publiée",
     "منشورة للعموم — لم يُنشر تعديلك بعد"),
    ("shared_publish_unconfirmed", "Sharing — not confirmed yet",
     "Partage — pas encore confirmé", "قيد النشر — لم يُؤكَّد بعد"),
    ("shared_still_public", "Still public — withdrawal not confirmed",
     "Toujours public — arrêt non confirmé", "لا تزال منشورة — لم يُؤكَّد إيقاف النشر"),
    ("shared_withdrawal_unconfirmed", "Withdrawal not confirmed",
     "Arrêt non confirmé", "لم يُؤكَّد إيقاف النشر"),
    # Promises cessation, and only cessation — see field_share_public_caption. All three already
    # say that and none of them should be "smoothed" into anything that sounds like deletion.
    ("menu_unshare_point", "Stop sharing", "Ne plus partager", "إيقاف النشر"),
    ("selected_destination", "▸ %1$s", "▸ %1$s", "▸ %1$s"),
    ("accuracy_suffix", " · ±%1$s m", " · ±%1$s m", " · ±%1$s م"),
    ("delete_dialog_title", "Delete this point?", "Supprimer ce point ?", "حذف هذه النقطة؟"),
    ("delete_dialog_body", "“%1$s” will be removed. %2$s",
     "« %1$s » sera supprimé. %2$s", "سيتم حذف «%1$s». %2$s"),
    ("delete", "Delete", "Supprimer", "حذف"),
    ("keep", "Keep", "Conserver", "الاحتفاظ"),

    ("SECTION", "Sort orders", None, None),
    ("sort_name_asc", "Name A–Z", "Nom A–Z", "الاسم أ–ي"),
    ("sort_name_desc", "Name Z–A", "Nom Z–A", "الاسم ي–أ"),
    ("sort_nearest", "Nearest first", "Les plus proches d’abord", "الأقرب أولاً"),
    ("sort_farthest", "Farthest first", "Les plus éloignés d’abord", "الأبعد أولاً"),
    ("sort_newest", "Newest first", "Les plus récents d’abord", "الأحدث أولاً"),
    ("sort_oldest", "Oldest first", "Les plus anciens d’abord", "الأقدم أولاً"),
    ("sort_recently_used", "Recently used", "Utilisés récemment", "المستخدمة مؤخراً"),

    ("SECTION", "Add and edit a point", None, None),
    ("add_point_title", "Add a point", "Ajouter un point", "إضافة نقطة"),
    ("edit_point_title", "Edit point", "Modifier le point", "تعديل النقطة"),
    ("back", "Back", "Retour", "رجوع"),
    ("field_name", "Name", "Nom", "الاسم"),
    ("field_name_placeholder", "Trailhead", "Départ du sentier", "بداية المسار"),
    ("field_latitude", "Latitude", "Latitude", "خط العرض"),
    ("field_longitude", "Longitude", "Longitude", "خط الطول"),
    ("field_latitude_placeholder", "48.8584    (N positive, S negative)",
     "48.8584    (N positif, S négatif)", "48.8584    (شمال موجب، جنوب سالب)"),
    ("field_longitude_placeholder", "2.2945    (E positive, W negative)",
     "2.2945    (E positif, O négatif)", "2.2945    (شرق موجب، غرب سالب)"),
    ("field_latitude_hint", "Or paste a whole coordinate here — both fields will fill.",
     "Ou collez ici une coordonnée complète — les deux champs se rempliront.",
     "أو ألصق هنا إحداثية كاملة — سيُملأ الحقلان معاً."),
    ("field_longitude_hint", "Between -180 and 180", "Entre -180 et 180", "بين ‎-180‎ و‎180‎"),
    ("error_not_a_number", "Not a number", "Ce n’est pas un nombre", "ليس رقماً"),
    ("error_latitude_range", "Must be between -90 and 90", "Doit être entre -90 et 90",
     "يجب أن يكون بين ‎-90‎ و‎90‎"),
    ("error_longitude_range", "Must be between -180 and 180", "Doit être entre -180 et 180",
     "يجب أن يكون بين ‎-180‎ و‎180‎"),
    ("read_as", "Read as %1$s and split into both fields.",
     "Interprété comme %1$s et réparti dans les deux champs.",
     "قُرئ بصيغة %1$s وقُسِّم على الحقلين."),
    ("save_point", "Save point", "Enregistrer le point", "حفظ النقطة"),
    ("save_changes", "Save changes", "Enregistrer les modifications", "حفظ التعديلات"),
    # The opt-in that makes the point public. The caption states exactly what becomes visible,
    # because a toggle without its consequence reads as private-by-default to the people who
    # most need to know it is not.
    #
    # It also states the part that cannot be undone, and states it here rather than beside the
    # off switch, because that is the only moment it is still avoidable. Stopping sharing is
    # cessation, not retrieval: it stops new people seeing the point, and does nothing about
    # anyone who already has it — every client caches the feed to disk, and "save as mine" makes
    # a permanent copy that is then an ordinary point of theirs.
    #
    # The app name is a placeholder fed from app_name, not spelled out. It was spelled out in all
    # three languages, and a rename then left this one string naming the app by a name it no
    # longer used — visible to users, in the sentence asking for their consent. A placeholder
    # cannot go stale; another literal would just re-arm the same trap for the next rename.
    ("field_share_public", "Share publicly", "Partager publiquement", "نشر للعموم"),
    ("field_share_public_caption",
     "Anyone using %1$s will see this point’s name, coordinates and note. You can stop sharing later — but anyone who already has it keeps their copy.",
     "Toute personne utilisant %1$s verra le nom, les coordonnées et la note de ce point. Vous pouvez arrêter le partage plus tard — mais toute personne qui l’a déjà en garde une copie.",
     "سيرى كل مستخدمي %1$s اسم هذه النقطة وإحداثياتها وملاحظتها. يمكنك إيقاف النشر لاحقاً — لكن من حصل عليها من قبل يحتفظ بنسخته."),
    ("plus_code_label", "Plus code %1$s", "Plus code %1$s", "‏Plus code %1$s"),
    ("mgrs_label", "MGRS %1$s", "MGRS %1$s", "‏MGRS %1$s"),
    ("pasteable_formats", "Pasteable formats", "Formats acceptés", "الصيغ المقبولة"),
    ("shortened_links_warning",
     "Shortened links (maps.app.goo.gl, bit.ly) can never work offline — only the server that made them knows where they point.",
     "Les liens raccourcis (maps.app.goo.gl, bit.ly) ne peuvent jamais fonctionner hors ligne : seul le serveur qui les a créés sait où ils mènent.",
     "الروابط المختصرة (maps.app.goo.gl، bit.ly) لا يمكن أن تعمل بدون اتصال — الخادم الذي أنشأها هو وحده من يعرف إلى أين تشير."),

    ("SECTION", "Coordinate format names, as shown by “Read as …”", None, None),
    ("format_decimal", "decimal degrees", "degrés décimaux", "درجات عشرية"),
    ("format_dms", "degrees, minutes, seconds", "degrés, minutes, secondes", "درجات ودقائق وثوان"),
    ("format_geo_uri", "a geo: link", "un lien geo:", "‏رابط ‎geo:"),
    ("format_plus_code", "a plus code", "un plus code", "‏plus code"),
    ("format_plus_code_short", "a short plus code", "un plus code abrégé", "‏plus code مختصر"),
    ("format_mgrs", "an MGRS reference", "une référence MGRS", "‏مرجع MGRS"),
    ("format_utm", "a UTM reference", "une référence UTM", "‏مرجع UTM"),
    ("format_map_url", "a map link", "un lien de carte", "رابط خريطة"),

    ("SECTION", "Parser problems", None, None),
    ("parse_shortened_link",
     "%1$s links are shortened — only their server knows where they point. Open the link once while you have signal, then copy the full address or the coordinates from it.",
     "Les liens %1$s sont raccourcis : seul leur serveur sait où ils mènent. Ouvrez le lien une fois avec du réseau, puis copiez l’adresse complète ou les coordonnées qu’elle contient.",
     "روابط %1$s مختصرة — خادمها هو وحده من يعرف إلى أين تشير. افتح الرابط مرة واحدة أثناء توفر الشبكة، ثم انسخ العنوان الكامل أو الإحداثيات منه."),
    ("parse_link_without_coordinates",
     "That link doesn’t contain coordinates, so it can’t be resolved without a connection. Open it while online and copy the coordinates.",
     "Ce lien ne contient pas de coordonnées : il ne peut pas être résolu sans connexion. Ouvrez-le avec du réseau et copiez les coordonnées.",
     "هذا الرابط لا يحتوي على إحداثيات، لذا لا يمكن تحليله بدون اتصال. افتحه أثناء الاتصال وانسخ الإحداثيات."),
    ("parse_malformed_plus_code", "Malformed plus code.", "Plus code mal formé.",
     "‏plus code غير صالح."),
    ("parse_short_plus_code_needs_fix",
     "“%1$s” is a shortened plus code. It needs your current position to resolve, and there is no position fix yet.",
     "« %1$s » est un plus code abrégé. Il a besoin de votre position actuelle pour être résolu, et la position n’est pas encore acquise.",
     "‏«%1$s» هو plus code مختصر. يحتاج إلى موقعك الحالي لتحليله، ولم يُحدَّد الموقع بعد."),
    ("parse_plus_code_unresolvable", "Could not resolve “%1$s”.",
     "Impossible de résoudre « %1$s ».", "تعذّر تحليل «%1$s»."),
    ("parse_mgrs_odd_digit_count", "MGRS needs an even number of digits.",
     "Une référence MGRS doit comporter un nombre pair de chiffres.",
     "يجب أن يحتوي مرجع MGRS على عدد زوجي من الأرقام."),
    ("parse_mgrs_invalid", "Not a valid MGRS reference.", "Référence MGRS non valide.",
     "‏مرجع MGRS غير صالح."),
    ("parse_utm_zone_out_of_range", "UTM zone must be 1-60.",
     "La zone UTM doit être comprise entre 1 et 60.", "يجب أن تكون منطقة UTM بين 1 و60."),
    ("parse_not_a_number", "Not a number.", "Ce n’est pas un nombre.", "ليس رقماً."),
    ("parse_latitude_out_of_range", "Latitude must be between -90 and 90.",
     "La latitude doit être comprise entre -90 et 90.",
     "يجب أن يكون خط العرض بين ‎-90‎ و‎90‎."),
    ("parse_longitude_out_of_range", "Longitude must be between -180 and 180.",
     "La longitude doit être comprise entre -180 et 180.",
     "يجب أن يكون خط الطول بين ‎-180‎ و‎180‎."),

    ("SECTION", "Snackbars", None, None),
    ("saved_point", "Saved “%1$s” — rename it from Destinations",
     "« %1$s » enregistré — vous pouvez le renommer depuis Destinations",
     "تم حفظ «%1$s» — يمكنك إعادة تسميته من «الوجهات»"),
    ("saved_point_with_accuracy", "Saved “%1$s” at ±%2$s m — rename it from Destinations",
     "« %1$s » enregistré à ±%2$s m — vous pouvez le renommer depuis Destinations",
     "تم حفظ «%1$s» بدقة ±%2$s م — يمكنك إعادة تسميته من «الوجهات»"),
    ("save_failed_fix_changed", "The fix changed while saving — try again",
     "La position a changé pendant l’enregistrement — réessayez",
     "تغيّر تحديد الموقع أثناء الحفظ — أعد المحاولة"),
    ("save_blocked_no_fix",
     "No position fix yet — nothing to save. Give the satellites a moment, or step outside.",
     "Position non acquise — rien à enregistrer. Laissez un moment aux satellites, ou sortez à l’extérieur.",
     "لم يُحدَّد الموقع بعد — لا شيء لحفظه. امهل الأقمار الصناعية لحظة، أو اخرج إلى العراء."),
    ("save_blocked_stale",
     "That fix is %1$s s old, so it’s where you were, not where you are. Waiting for a fresh one.",
     "Cette position date de %1$s s : c’est là où vous étiez, pas là où vous êtes. En attente d’une position récente.",
     "مضى على تحديد الموقع هذا %1$s ثانية، فهو يمثل مكانك السابق لا مكانك الحالي. في انتظار تحديد جديد."),
    ("deleted_point", "Deleted “%1$s”", "« %1$s » supprimé", "تم حذف «%1$s»"),
    ("undo", "Undo", "Annuler", "تراجع"),

    ("SECTION", "Degraded-subsystem banners", None, None),
    ("degraded_declination", "Using magnetic north — declination model unavailable",
     "Nord magnétique utilisé — modèle de déclinaison indisponible",
     "يُستخدم الشمال المغناطيسي — نموذج الانحراف غير متوفر"),
    ("degraded_store_read", "Saved destinations could not be read",
     "Impossible de lire les destinations enregistrées", "تعذّرت قراءة الوجهات المحفوظة"),
    ("degraded_no_location", "No position updates — %1$s",
     "Aucune mise à jour de position — %1$s", "لا توجد تحديثات للموقع — %1$s"),
    ("degraded_no_compass", "Compass unavailable — heading comes from GPS course while moving",
     "Boussole indisponible — le cap provient du GPS pendant le déplacement",
     "البوصلة غير متوفرة — يُؤخذ الاتجاه من GPS أثناء الحركة"),

    ("SECTION", "Permission gate", None, None),
    ("permission_title_precise", "Precise location needed", "Position précise requise",
     "مطلوب تحديد دقيق للموقع"),
    ("permission_title_location", "Location permission", "Autorisation de localisation",
     "إذن الوصول إلى الموقع"),
    ("permission_body_approximate",
     "You granted approximate location. An arrow pointing at a destination needs precise GPS — approximate is accurate to about a kilometre, which would point you the wrong way.",
     "Vous avez accordé la localisation approximative. Une flèche qui pointe vers une destination a besoin du GPS précis : l’approximatif est précis à environ un kilomètre, ce qui vous enverrait dans la mauvaise direction.",
     "لقد منحت إذن الموقع التقريبي. السهم الذي يشير إلى وجهة يحتاج إلى GPS دقيق — الموقع التقريبي دقته نحو كيلومتر واحد، وهو ما سيوجّهك في الاتجاه الخطأ."),
    # This claimed "Nothing is sent anywhere — this build has no internet permission at all".
    # Both halves stopped being true when map downloads landed: the manifest has declared
    # INTERNET since then, and the app does make requests. It now says the thing that is both
    # true and what the user is actually asking at a location prompt — that their POSITION never
    # leaves the device — and names the one thing that does use the connection, in the same words
    # as about_offline, so the two cannot drift apart again.
    #
    # The general rule, learned twice now: a reassurance phrased as "this app cannot do X" dates
    # the moment X is added, while one phrased as "X is the only thing that does" survives. Any
    # new network use must be added to BOTH strings or neither is true.
    #
    # Which is what happened next. Shared points made two clauses here false at once — the
    # enumeration ("the only thing") and, for the first time, the flat promise that the position
    # is never sent anywhere. It is still true by default and still true for anyone who never
    # touches the share switch, but "never" was doing work the app can no longer back, so it now
    # names the one act that sends a position and says it is the user's.
    #
    # The app is also no longer named here. It was spelled out in all three languages, which is
    # how a rename left the sharing caption naming the app by a name it had stopped using. There
    # is nothing in this sentence that needs the name, so the safest fix was to remove the
    # dependency rather than parameterise it.
    ("permission_body_initial",
     "Your position is read from the GPS satellites directly, and is not sent anywhere unless you choose to share a saved point. The internet is otherwise used only for downloading an offline area when you ask, and for refreshing the shared points when you open the map.",
     "Votre position est lue directement depuis les satellites GPS et n’est envoyée nulle part, sauf si vous choisissez de partager un point enregistré. Internet ne sert par ailleurs qu’à télécharger une zone hors ligne à votre demande, et à actualiser les points partagés quand vous ouvrez la carte.",
     "يُقرأ موقعك مباشرة من الأقمار الصناعية، ولا يُرسل إلى أي جهة إلا إذا اخترت مشاركة نقطة محفوظة. وبخلاف ذلك لا يُستخدم الإنترنت إلا لتنزيل منطقة للاستخدام دون اتصال عندما تطلب ذلك، ولتحديث النقاط المنشورة عند فتح الخريطة."),
    ("permission_grant_precise", "Grant precise location", "Accorder la position précise",
     "منح إذن الموقع الدقيق"),
    ("permission_continue", "Continue", "Continuer", "متابعة"),
    ("permission_open_settings", "Open app settings", "Ouvrir les paramètres de l’application",
     "فتح إعدادات التطبيق"),

    ("SECTION", "Map tab", None, None),
    # Areas are named by the places they cover, never by country or territory. Two reasons, and
    # either alone would be sufficient: a user recognises their own city faster than a country
    # label and it answers "does this cover me?" directly, and the larger area spans territory
    # whose status is disputed, so any country name would be taking a position. Place names are
    # proper nouns and are given in each language's own form, not transliterated from English.
    ("map_ready", "Map ready: %1$s", "Carte prête : %1$s", "الخريطة جاهزة: %1$s"),
    # One control, one meaning: face north and start following the heading again.
    ("cd_face_north", "Face north and follow my heading",
     "Orienter vers le nord et suivre mon cap",
     "التوجه نحو الشمال ومتابعة اتجاهي"),
    ("map_centre_on_me", "Centre on me", "Centrer sur moi", "التوسيط على موقعي"),
    ("map_back_to_arrow", "Back to the arrow", "Retour à la flèche", "العودة إلى السهم"),
    # The tap-inspect card for a shared dot, and the confirmation for saving one. The card's
    # primary action reuses navigate_here; only the shared-specific strings live here.
    ("shared_card_label", "Shared by another user", "Partagé par un autre utilisateur",
     "منشور من مستخدم آخر"),
    ("shared_card_save", "Save as mine", "Ajouter à mes points", "إضافة إلى نقاطي"),
    ("shared_saved_mine", "Saved to your destinations", "Enregistré dans vos destinations",
     "تم الحفظ في وجهاتك"),
    ("map_none_title", "No map for this area yet", "Pas encore de carte pour cette zone",
     "لا توجد خريطة لهذه المنطقة بعد"),
    ("map_none_arrow_works", "The arrow still works — it doesn’t need maps.",
     "La flèche fonctionne toujours — elle n’a pas besoin de cartes.",
     "لا يزال السهم يعمل — فهو لا يحتاج إلى خرائط."),
    ("map_none_prompt", "Download an offline area to see the map here.",
     "Téléchargez une zone hors ligne pour voir la carte ici.",
     "نزّل منطقة للاستخدام دون اتصال لرؤية الخريطة هنا."),
    ("map_none_explanation",
     "Areas are downloaded once while you’re online, then work offline forever after.",
     "Les zones se téléchargent une fois en ligne, puis fonctionnent hors ligne pour toujours.",
     "تُنزَّل المناطق مرة واحدة أثناء الاتصال، ثم تعمل بدون اتصال إلى الأبد."),
    ("map_open_region_list", "Open the areas list", "Ouvrir la liste des zones",
     "فتح قائمة المناطق"),
    ("SECTION", "Offline areas", None, None),
    ("areas_title", "Offline areas", "Zones hors ligne", "المناطق دون اتصال"),
    # This screen presents over the MAP, so its exit returns there. Deliberately not
    # map_back_to_arrow, which labels a different button with a different destination.
    ("areas_back_to_map", "Back to the map", "Retour à la carte",
     "العودة إلى الخريطة"),
    ("area_larger_places", "Tangier · Casablanca · Marrakech · Agadir · Dakhla",
     "Tanger · Casablanca · Marrakech · Agadir · Dakhla",
     "طنجة · الدار البيضاء · مراكش · أكادير · الداخلة"),
    ("area_smaller_places", "Nouakchott · Nouadhibou · Zouérat · Néma",
     "Nouakchott · Nouadhibou · Zouérate · Néma",
     "نواكشوط · نواذيبو · الزويرات · النعمة"),
    ("area_covers_you", "Your current position is inside this area",
     "Votre position actuelle se trouve dans cette zone",
     "موقعك الحالي داخل هذه المنطقة"),
    # Two areas can legitimately both contain the user — the overlap band is the Atlantic coast
    # road south, not an edge case. Saying "covers you" twice with nothing distinguishing them
    # describes geometry rather than behaviour. These two say which one the app will actually use.
    ("area_also_covers", "Also covers your position",
     "Couvre aussi votre position", "تغطي موقعك أيضاً"),
    ("area_recommended", "Recommended for your position",
     "Recommandée pour votre position", "موصى بها لموقعك"),
    ("area_position_unknown", "No position fix yet, so coverage can’t be checked",
     "Position non acquise : impossible de vérifier la couverture",
     "لم يُحدَّد الموقع بعد، لذا يتعذّر التحقق من التغطية"),

    # Levels are named and described by what they contain. A user cannot act on "z12".
    ("level_standard", "Standard", "Standard", "قياسي"),
    ("level_detailed", "Detailed", "Détaillé", "مفصّل"),
    ("level_standard_summary", "Roads, tracks, towns and villages",
     "Routes, pistes, villes et villages",
     "الطرق والمسالك والمدن والقرى"),
    ("level_detailed_summary", "Adds footpaths, isolated buildings and seasonal watercourses",
     "Ajoute les sentiers, les bâtiments isolés et les cours d’eau saisonniers",
     "يضيف المسارات والمباني المعزولة والأودية الموسمية"),
    ("areas_one_level_note",
     "One detail level per area. Choosing another replaces the one you have.",
     "Un seul niveau de détail par zone. En choisir un autre remplace celui que vous avez.",
     "مستوى تفصيل واحد لكل منطقة. اختيار مستوى آخر يستبدل المستوى الحالي."),
    ("areas_installed", "Installed", "Installée", "مثبَّتة"),
    ("areas_storage_used", "%1$s used by maps", "%1$s utilisés par les cartes",
     "%1$s تستخدمها الخرائط"),
    ("areas_free_space", "%1$s free on this device", "%1$s libres sur cet appareil",
     "%1$s متاحة على هذا الجهاز"),
    # The diagnostic that tells a bad download apart from a bad renderer. Both are first exercised
    # on the user's device at the same moment, and "the map is blank" does not distinguish them.
    # This re-reads the installed archive's header and reports what it found.
    ("areas_file_ok", "File verified · %1$s on disk", "Fichier vérifié · %1$s sur le disque",
     "تم التحقق من الملف · %1$s على القرص"),
    ("areas_file_bad", "This file failed its check (%1$s). Delete and download it again.",
     "Ce fichier a échoué à la vérification (%1$s). Supprimez-le et retéléchargez-le.",
     "فشل هذا الملف في التحقق (%1$s). احذفه ونزّله مرة أخرى."),
    ("areas_download", "Download", "Télécharger", "تنزيل"),
    ("areas_delete", "Delete", "Supprimer", "حذف"),
    ("areas_switch", "Switch to this level", "Passer à ce niveau", "التبديل إلى هذا المستوى"),

    # Metered-data warning. Names the real number, because "a large download" is not actionable
    # and "133 MB" is. It warns and lets them proceed: it is their data, and someone deliberately
    # downloading a map before heading somewhere with no signal is the case that matters most.
    ("metered_title", "You’re on mobile data", "Vous êtes en données mobiles",
     "أنت تستخدم بيانات الجوال"),
    ("metered_body", "This download will use %1$s of mobile data.",
     "Ce téléchargement utilisera %1$s de données mobiles.",
     "سيستهلك هذا التنزيل %1$s من بيانات الجوال."),
    ("metered_continue", "Download anyway", "Télécharger quand même", "التنزيل على أي حال"),
    ("metered_wait", "Wait for Wi-Fi", "Attendre le Wi-Fi", "الانتظار حتى توفر Wi-Fi"),

    ("download_progress", "%1$s of %2$s", "%1$s sur %2$s", "%1$s من %2$s"),
    ("download_verifying", "Checking the file…", "Vérification du fichier…",
     "جارٍ التحقق من الملف…"),
    ("download_cancel", "Cancel", "Annuler", "إلغاء"),
    ("download_resume", "Resume", "Reprendre", "استئناف"),
    ("download_not_published", "Not available yet", "Pas encore disponible", "غير متاح بعد"),
    ("download_no_space", "Not enough space — needs %1$s, %2$s free",
     "Espace insuffisant — il faut %1$s, %2$s libres",
     "المساحة غير كافية — يلزم %1$s، والمتاح %2$s"),
    ("download_network_failed",
     "Connection lost. Your progress is kept — resume when you have signal.",
     "Connexion perdue. Votre progression est conservée — reprenez dès que vous aurez du réseau.",
     "انقطع الاتصال. تم حفظ تقدّمك — استأنف عند توفر الشبكة."),
    ("download_corrupt", "The file arrived damaged and was deleted. Nothing was installed.",
     "Le fichier est arrivé endommagé et a été supprimé. Rien n’a été installé.",
     "وصل الملف تالفًا وتم حذفه. لم يُثبَّت أي شيء."),
    ("download_server_error", "The server refused the download (%1$s).",
     "Le serveur a refusé le téléchargement (%1$s).",
     "رفض الخادم التنزيل (%1$s)."),
    # Shown while the replacement is downloading, so nobody thinks the old one is already gone.
    ("download_replacing", "Your current level stays until this finishes",
     "Votre niveau actuel reste en place jusqu’à la fin",
     "يبقى مستواك الحالي حتى ينتهي هذا"),

    ("map_remind_when_online", "Remind me when I’m online",
     "Me le rappeler quand je serai en ligne", "ذكّرني عند الاتصال بالإنترنت"),

    ("SECTION", "Notification", None, None),
    ("navigating", "Navigating", "Navigation en cours", "جارٍ التوجيه"),
    ("notification_text", "Navigating — open for the arrow and distance",
     "Navigation en cours — ouvrez l’application pour la flèche et la distance",
     "جارٍ التوجيه — افتح التطبيق لرؤية السهم والمسافة"),
    ("stop", "Stop", "Arrêter", "إيقاف"),
    ("channel_navigation", "Navigation", "Navigation", "التوجيه"),
    ("channel_navigation_description", "Keeps the arrow running while the screen is off.",
     "Maintient la flèche active lorsque l’écran est éteint.",
     "يُبقي السهم يعمل عندما تكون الشاشة مطفأة."),

    ("SECTION", "Settings and About", None, None),
    ("settings_title", "Settings", "Réglages", "الإعدادات"),
    ("settings_language", "Language", "Langue", "اللغة"),
    ("settings_about", "About", "À propos", "حول التطبيق"),
    ("about_version", "Version", "Version", "الإصدار"),
    ("about_declination_source", "Magnetic model", "Modèle magnétique", "النموذج المغناطيسي"),
    ("about_declination_framework", "Android built-in (WMM2020)",
     "Intégré à Android (WMM2020)", "‏مدمج في أندرويد (WMM2020)"),
    # No country names anywhere the user can see. The fact is unchanged; only the framing is.
    ("about_declination_note",
     "Across the area this app covers, magnetic declination differs from the current model by under 0.2°, which is smaller than the model’s own uncertainty.",
     "Sur la zone couverte par cette application, la déclinaison magnétique s’écarte du modèle actuel de moins de 0,2°, soit moins que l’incertitude propre au modèle.",
     "في المنطقة التي يغطيها هذا التطبيق، يختلف الانحراف المغناطيسي عن النموذج الحالي بأقل من 0.2 درجة، وهو أقل من هامش عدم اليقين في النموذج نفسه."),
    # Corrected twice now, both times because something was added to the app that this sentence
    # had promised did not exist.
    #
    # First it said "never uses the internet — it has no internet permission", which stopped
    # being true the moment map downloads landed. Then it said downloading an area was the ONLY
    # thing that used the connection, and shared points made that false as well: opening the map
    # refreshes the feed, and a point the user opted into sharing is transmitted.
    #
    # The rule the comment on permission_body_initial states applies here in full, and this is
    # the second time it has had to be applied: ANY new network use must be added to BOTH
    # strings, or neither of them is true. An enumeration is only a reassurance while it is
    # complete, and a stale one is worse than none, because the user has no way to check it.
    ("about_offline",
     "Navigating never uses the internet. Downloading an offline area, and publishing a point you chose to share, happen only when you ask. Opening the map also refreshes the points other people have shared.",
     "La navigation n’utilise jamais Internet. Le téléchargement d’une zone hors ligne et la publication d’un point que vous avez choisi de partager n’ont lieu qu’à votre demande. Ouvrir la carte actualise aussi les points partagés par d’autres.",
     "لا يستخدم التوجّه الإنترنت إطلاقاً. تنزيل منطقة للاستخدام دون اتصال ونشر نقطة اخترت مشاركتها لا يحدثان إلا عندما تطلب ذلك. كما أنّ فتح الخريطة يحدّث النقاط التي شاركها آخرون."),
    # ODbL requires attribution wherever the map is shown, not buried in a licences page.
    ("about_map_attribution", "Map data: © OpenStreetMap contributors, ODbL.",
     "Données cartographiques : © les contributeurs d’OpenStreetMap, ODbL.",
     "بيانات الخريطة: © مساهمو OpenStreetMap، رخصة ODbL."),
    ("about_attribution", "Magnetic model: NOAA/NGA World Magnetic Model, public domain.",
     "Modèle magnétique : World Magnetic Model de la NOAA/NGA, domaine public.",
     "النموذج المغناطيسي: World Magnetic Model من NOAA/NGA، ملكية عامة."),

    ("SECTION", "Language picker", None, None),
    ("language_picker_title", "Choose your language", "Choisissez votre langue", "اختر لغتك"),
    ("language_picker_subtitle", "You can change this later in Settings.",
     "Vous pourrez la changer plus tard dans les Réglages.",
     "يمكنك تغييرها لاحقاً من الإعدادات."),
    ("language_picker_confirm", "Continue", "Continuer", "متابعة"),

    ("SECTION", "Diagnostics panel", None, None),
    ("diagnostics_title", "Diagnostics", "Diagnostic", "التشخيص"),
    ("diagnostics_close", "Close", "Fermer", "إغلاق"),
    ("diagnostics_hint", "Long-press the status chips at any time to open or close this.",
     "Appui long sur les indicateurs d’état pour ouvrir ou fermer ce panneau.",
     "اضغط مطوّلاً على مؤشرات الحالة في أي وقت لفتح هذه اللوحة أو إغلاقها."),
    ("diag_arrow_mode", "arrow mode", "mode de la flèche", "وضع السهم"),
    ("diag_arrow_angle", "arrow angle", "angle de la flèche", "زاوية السهم"),
    ("diag_arbiter_mode", "heading source", "source du cap", "مصدر الاتجاه"),
    ("diag_heading_smoothed", "heading (smoothed)", "cap (lissé)", "الاتجاه (بعد التنعيم)"),
    ("diag_course_chip", "course from receiver", "cap du récepteur",
     "الاتجاه من المستقبِل"),
    ("diag_course_derived", "course from movement", "cap calculé par le déplacement",
     "الاتجاه من الحركة"),
    ("diag_course_trust", "receiver course trust", "confiance dans le cap du récepteur",
     "الثقة باتجاه المستقبِل"),
    ("diag_course_trusted", "trusted", "fiable", "موثوق"),
    ("diag_course_distrusted", "stale — using movement", "obsolète — cap calculé utilisé",
     "قديم — يُستخدم الاتجاه من الحركة"),
    ("diag_compass_raw", "compass raw", "boussole brute", "البوصلة (خام)"),
    ("diag_compass_smoothed", "compass smoothed", "boussole lissée", "البوصلة (بعد التنعيم)"),
    ("diag_raw_minus_smoothed", "raw − smoothed", "brut − lissé", "الخام − المنعَّم"),
    ("diag_compass_sensor", "compass sensor", "capteur de boussole", "مستشعر البوصلة"),
    ("diag_sample_rate", "sample rate", "fréquence d’échantillonnage", "معدل أخذ العينات"),
    ("diag_smoothing_tau", "smoothing constant", "constante de lissage", "ثابت التنعيم"),
    ("diag_sensors_present", "sensors present", "capteurs présents", "المستشعرات المتوفرة"),
    ("diag_magnetometer_ok", "magnetometer ok", "magnétomètre ok", "المغنيطومتر سليم"),
    ("diag_declination", "declination", "déclinaison", "الانحراف المغناطيسي"),
    ("diag_declination_source", "declination source", "source de la déclinaison",
     "مصدر الانحراف"),
    ("diag_bearing_to_destination", "bearing to destination", "relèvement vers la destination",
     "الاتجاه نحو الوجهة"),
    ("diag_distance", "distance", "distance", "المسافة"),
    ("diag_fix", "fix", "position", "تحديد الموقع"),
    ("diag_fix_accuracy", "fix accuracy", "précision de la position", "دقة التحديد"),
    ("diag_fix_age", "fix age", "ancienneté de la position", "عمر التحديد"),
    ("diag_fix_quality", "fix quality", "qualité de la position", "جودة التحديد"),
    ("diag_provider", "provider", "fournisseur", "المزوّد"),
    ("diag_satellites", "satellites", "satellites", "الأقمار الصناعية"),
    ("diag_gps_enabled", "gps enabled", "GPS activé", "‏GPS مفعّل"),
    ("diag_location_job", "location running", "acquisition active", "خدمة الموقع تعمل"),
    ("diag_heading_job", "compass running", "boussole active", "خدمة البوصلة تعمل"),
    ("diag_heading_updates", "heading updates", "mises à jour du cap", "تحديثات الاتجاه"),
    ("diag_fix_updates", "fix updates", "mises à jour de position", "تحديثات الموقع"),
    ("diag_degraded", "degraded", "dégradations", "أعطال جزئية"),
    ("diag_none", "none", "aucune", "لا شيء"),
    ("diag_seconds", "%1$s s", "%1$s s", "%1$s ث"),
    ("diag_millis", "%1$s ms", "%1$s ms", "%1$s م.ث"),
    ("diag_hertz", "%1$s Hz", "%1$s Hz", "%1$s هرتز"),
    ("diag_degrees", "%1$s°", "%1$s°", "%1$s°"),
    ("diag_accuracy_meters", "±%1$s m", "±%1$s m", "±%1$s م"),
    ("diag_unavailable", "—", "—", "—"),
]

# The 16-point rose. Latin in Arabic on purpose: there is no compact Arabic convention for the
# intercardinals ("شمال شمال شرق" is three words in a chip that must stay on one line), the
# bearing beside it is already in Latin digits, and Latin points are what regional marine and
# aviation practice uses.
COMPASS_POINTS = {
    "en": ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
           "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"],
    "fr": ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
           "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO"],
    "ar": ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
           "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"],
}

# Strings whose meaning is safety-carrying.
#
# The criterion, stated in full because it has just been widened: **a mistranslation could leave
# the user confidently believing something false that harms them.**
#
# It used to read "position and fix quality" — how good the position is, that it is old, that a
# subsystem has failed, that the app does not know something. Everything that qualified under
# that reading still qualifies; it was simply too narrow a description of what it was already
# doing, and it stopped being adequate when this app gained something to publish.
#
# A user who believes their camp is private while it is still on every other user's map is in
# the same shape of danger as a user who believes a stale position is current: confident, wrong,
# and acting on it. It is arguably worse, because a stale position corrects itself the moment the
# next fix lands and a wrong belief about publication does not correct itself at all.
#
# What follows from the mark is unchanged: these are translated literally at the cost of
# elegance, and read together in TRANSLATIONS.md. A smooth translation that softens "this is
# where you were, not where you are" is the dangerous kind, and so is one that renders
# "withdrawal not confirmed" as anything a reader could mistake for "withdrawn".
#
# No script can infer this: it is a reading of what each string claims. A new string is NOT
# marked until it is added here deliberately, and `emit_translations.py` says so in the document
# rather than implying the marking is automatic. It does check that every key below still exists
# in the table, so a rename cannot silently drop a mark.
SAFETY_KEYS = {
    # Saving a point when the fix cannot support it.
    "save_my_location_no_fix", "save_my_location_stale",
    "save_failed_fix_changed", "save_blocked_no_fix", "save_blocked_stale",
    # What the needle is doing and why.
    "arrived_explanation", "magnetic_north_notice", "compass_unreliable", "no_compass_body",
    "heading_compass_magnetic", "heading_compass_uncalibrated",
    # Fix quality and age.
    "chip_accuracy_weak", "chip_stale_fix", "chip_no_fix",
    "position_out_of_date", "position_stale", "position_none",
    "distance_under", "distances_stale_warning",
    # The app not knowing something, said out loud.
    "value_unknown", "area_position_unknown",
    # Location unavailable, and the wait before it is available.
    "location_off_title", "location_off_body", "acquiring_title", "acquiring_body",
    # Degraded subsystems and a permission that silently halves the app.
    "degraded_declination", "degraded_no_compass", "permission_body_approximate",
    # A map the user may believe is installed and usable when it is not.
    "areas_file_bad", "download_corrupt",
    # The one diagnostics row that reports a source being distrusted rather than labelling one.
    "diag_course_distrusted",
    # Whether a saved point is on the internet, and what the user was promised before it went.
    # Marked under the widened criterion above. The consent caption and the switch label are
    # here because a mistranslation of either causes a publication nobody intended; the four
    # status strings because a mistranslation of any of them tells the user a point is private
    # when it is not, or public when it never was. The two unconfirmed states are the ones to
    # watch: neither may be rendered as anything a reader could take for a completed act.
    "field_share_public", "field_share_public_caption", "menu_unshare_point",
    "shared_badge", "shared_edit_unpublished", "shared_publish_unconfirmed",
    "shared_still_public", "shared_withdrawal_unconfirmed",
    # What uses the connection, and whether the position leaves the device. Both are flat
    # promises about network behaviour that the user has no way to verify, and both have now
    # been made false once by a feature landing — see the comments on each.
    "about_offline", "permission_body_initial",
}
