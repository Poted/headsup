# Sweet Spot: scenariusze użycia i flow (materiał do przeglądu UI/UX)

Dokument opisuje, kto i jak używa aplikacji, jak wygląda obecny flow, gdzie się on rozjeżdża
i jaką kolejność pracy aplikacja powinna podpowiadać. Nazwy w interfejsie są po angielsku, tak
jak w aplikacji. Stan na listopad 2026 (po 1.0.0).

## 1. Czym jest aplikacja

Edytor wideo i zdjęć na Androida, offline, z ambicją „DaVinci w telefonie”, ale do obsługi
jednym kciukiem. Dwa rodzaje projektów:

- **Video**: oś czasu z klipami (telefon, Osmo, dron), opcjonalnie dane z zegarka Garmin
  (FIT) na dashboardzie. Eksport do pliku wideo (SDR, 10-bit, HLG).
- **Photos (album)**: zestaw zdjęć do wybrania (Auto pick, gwiazdki), poprawienia i
  wyeksportowania (także karuzela Instagram).

Główna nawigacja (dolny pasek): **Project · Edit · Adjust · Overlays · Audio · Export**.

| Zakładka | Co zawiera |
|---|---|
| Project | pliki, kolejność klipów, grupy kamer, zapis i wersje projektu |
| Edit | Cut (przycinanie, podział, flagi), Speed (z kierunkiem), Steady, Fades, Frame (kształt filmu, kadr klipu, ruch kadru: Ken Burns/Follow), Frame rate, Moments |
| Adjust | Layers / Keys / Applies to, potem sekcje: Looks, Light, Colour, Presence, Mixer, Film, Effects, Tools |
| Overlays | teksty, naklejki, mapa trasy, dashboard, wykres |
| Audio | muzyka, głośność, ducking, LUFS |
| Export | presety (Vertical, YouTube), rozdzielczość, kodek, 10-bit/HLG |

Wspólne gesty podglądu: przytrzymanie palca pokazuje oryginał, przytrzymanie i przesunięcie w
bok daje linię przed/po; uszczypnięcie przybliża (zdjęcia, maski, próbkowanie); ✓ / ↶ / ↷ u
góry podglądu.

## 2. Kto z niej korzysta (persony)

1. **Twórca krótkich form (Reels/TikTok)**. Telefon, kilka ujęć, chce „filmowego” wyglądu w
   minutę. Nie zna pojęć color gradingu. Oczekuje: wybór looku, napisy, muzyka, eksport 9:16.
2. **Sportowiec z kamerką i zegarkiem**. Osmo/GoPro i Garmin. Chce dashboardu z danymi
   (tętno, prędkość) i dynamicznego montażu. Kolor ma znaczenie drugorzędne.
3. **Hobbysta-kolorysta**. Zna Lightroom lub DaVinci. Chce precyzji: krzywe, koła, maski,
   printer lights, porównanie przed/po, presety. Pracuje dłużej nad jednym ujęciem.
4. **Fotograf z albumem**. Wraca z wyjazdu z 300 zdjęciami. Chce wybrać najlepsze, ujednolicić
   kolory, przyciąć (także w karuzelę) i wyeksportować.

## 3. Scenariusze (jak powinno wyglądać dobre użycie)

### S1. Szybki filmowy look do Reelsa (persona 1)
1. Nowy projekt Video → wybór 3–5 klipów.
2. Edit › Cut: przycięcie; Speed: 0.25× dla ujęcia 120 fps.
3. Export preset **Vertical** (9:16, 30 fps) — najlepiej już na starcie, bo zmienia kadr.
4. Adjust › Looks › Cinematic: **Analyse clip** → aplikacja proponuje 3 looki (obwódka) →
   wybór → suwak Strength.
5. Overlays: tekst. Audio: muzyka.
6. Export.

Oczekiwany czas: 2–3 minuty. Dziś realny, ale kroki 3 i 4 trzeba umieć znaleźć.

### S2. Montaż z dashboardem (persona 2)
1. Nowy projekt → klipy + plik FIT → automatyczna synchronizacja czasu.
2. Project: sprawdzenie, czy każdy klip trafił w dane (pewność dopasowania).
3. Edit: cięcia, Moments (najlepsze chwile), Speed ramp.
4. Overlays › Dashboard: styl, metryki; Route map.
5. Adjust: grupa kamer (Group) dopasowana Shot match do jednej referencji.
6. Export YouTube.

### S3. Staranny grade jednego ujęcia (persona 3)
Kolejność, którą stosuje kolorysta (i którą aplikacja powinna podpowiadać):
1. **Normalizacja**: profil kamery (Looks › Profiles) lub LUT techniczny, ekspozycja,
   balans bieli (Light, Colour). Printer lights do korekty dominanty.
2. **Balans między ujęciami**: Shot match, grupy kamer.
3. **Korekty lokalne**: maski (niebo, osoba, pędzel).
4. **Look kreatywny**: Cinematic look albo własny (koła, split tone, Colour harmony).
5. **Tekstura i światło**: grain, halation, glow, mist, anamorphic, gate weave.
6. **Porównanie** przed/po i z innymi ujęciami, zapis jako preset.

### S4. Album z wyjazdu (persona 4)
1. Nowy projekt Photos → import → **Auto pick** (gwiazdki, grupy podobnych).
2. Przegląd w albumie, zaznaczenie wybranych.
3. Edycja jednego zdjęcia → **Save this look** (My presets) → zastosowanie do zaznaczonych albo
   Shot match zaznaczonych do wzorca.
4. Kadrowanie: Aspect (4:5, 1:1, Custom, przeciąganie krawędzi), Carousel.
5. Eksport zaznaczonych.

## 4. Obecny flow i miejsca, gdzie się rozjeżdża

Obserwacje z testów (część już poprawiona, zostawiona jako tło):

1. **Kolejność pracy nie jest podpowiadana.** Typowa ścieżka nowego użytkownika: projekt →
   Adjust › Light (zabawa ekspozycją) → potem odkrywa Looks › Cinematic → Analyse. Look jest
   wtedy nakładany na już ręcznie przesunięte suwaki, a analiza mierzy obraz po tych zmianach.
   Lepiej: Analyse i wybór looku na początku (albo zaraz po normalizacji), a suwaki jako
   dopracowanie.
2. **Looks są głęboko.** Cinematic jest w Adjust › sekcja Looks › kategoria Cinematic. Dla
   persony 1 to najważniejsza funkcja w Adjust, a jest na trzecim poziomie.
3. **Trzy różne mechanizmy „całego wyglądu”**: Cinematic look, Profiles, My presets (plus LUT
   w Tools i Colour harmony). Każdy z innym modelem cofania. Od tej wersji Cinematic, Colour
   harmony i My presets mają **None** i pamiętają się w klipie, ale Profiles i LUT działają
   inaczej (Amount / wybór z listy).
4. **Wiele trybów „przytrzymaj, żeby porównać”.** Przytrzymanie podglądu, przytrzymanie suwaka,
   przytrzymanie kółka korekty — każde porównuje co innego (całość / jedna korekta / jeden
   look). Działa, ale nie jest wytłumaczone w jednym miejscu.
5. **Przyciski Undo/Done** w panelach (relikt sesji) — usunięte w Cinematic; warto sprawdzić,
   czy nie zostały w innych miejscach.
6. **Ustawienia projektu rozproszone**: kształt osi (Edit › Frame), frame rate (Edit › Frame
   rate), preset eksportu (Export) zmieniają się nawzajem (np. Vertical ustawia 9:16 i 30 fps).
   Użytkownik ustawia kadr, a potem preset go nadpisuje.
7. **Opisy techniczne**: tam, gdzie liczby są potrzebne (fps, klatki), opis musi mówić,
   co z tego wynika („co druga klatka”, „0.5× dla pełnego slow motion”), a nie samą arytmetykę.
8. **Kadrowanie zdjęcia**: do niedawna Custom tylko przez wpisanie proporcji; teraz można
   przeciągać krawędzie i rogi kadru. Do sprawdzenia, czy uchwyty są wystarczająco widoczne i
   nie kolidują z przesuwaniem obrazu.
9. **Usuwanie bez potwierdzenia**: ✕ przy presecie usuwał go na stałe (poprawione: Manage +
   potwierdzenie). Warto przejrzeć inne ✕ w aplikacji (teksty, maski, flagi).
10. **Gesty w podglądzie konkurują**: przesuwanie przybliżonego obrazu, przytrzymanie do
    porównania, przeciąganie krawędzi kadru, malowanie maski, przesuwanie tekstu. Reguły są w
    kodzie, ale użytkownik ich nie widzi.

## 5. Proponowany kierunek (do oceny przez UX)

Propozycje, nie decyzje:

1. **Ścieżka startowa projektu wideo**: po imporcie krótki krok „Gdzie publikujesz?” (Reels /
   YouTube / tylko zapis) ustawiający kształt i fps od razu, zanim użytkownik zacznie kadrować.
2. **Adjust otwiera się na „Look”** dla nowego klipu: Analyse uruchamiane automatycznie przy
   pierwszym wejściu, trzy propozycje + None na górze, a sekcje Light/Colour/… jako
   „Dopracuj”. Kolorysta (persona 3) może przełączyć na widok pełny.
3. **Jeden model warstw wyglądu**: Profile → (Printer lights, korekty) → Look → Harmony →
   Preset, każdy z None i suwakiem siły, widoczne jako lista warstw klipu (jak w DaVinci
   węzły, ale liniowo). Dziś te warstwy istnieją w kodzie, ale nie są pokazane razem.
4. **Jedno miejsce na „porównaj”**: stały przycisk ⇆ przy podglądzie (przytrzymaj = oryginał,
   przeciągnij = linia), a przytrzymania suwaków jako dodatek.
5. **Ustawienia projektu w jednym miejscu** (kształt, fps, kolor wyjścia), z Export jako
   podsumowaniem, nie miejscem zmian.
6. **Album jako osobny tryb**: dla persony 4 flow to wybór → ujednolicenie → kadr → eksport;
   zakładki Edit/Audio/Overlays są tam w większości zbędne.
7. **Gesty**: krótka plansza przy pierwszym użyciu albo stały znak ⓘ przy podglądzie z
   listą gestów dla aktywnego narzędzia.

## 6. Czego oczekujemy od przeglądu

- Mapa ekranów i propozycja nowej hierarchii (co na pierwszym poziomie, co głębiej).
- Flow dla S1 i S4 w maksymalnie kilku dotknięciach.
- Spójny model cofania i „None” dla wszystkiego, co nakłada cały wygląd.
- Spójny język opisów (ⓘ) i komunikatów: co to robi i co z tego wynika dla użytkownika.
- Lista miejsc, gdzie gesty się gryzą, i propozycja reguł.

## 7. Mapa ekranów po przeglądzie IA (listopad 2026, po 1.0.0)

Zasada: **zakładka → sekcja → narzędzie**, najwyżej trzy poziomy, a każdy poziom grupuje według
tego, co użytkownik chce zrobić, a nie według tego, jak to działa w kodzie. W każdym panelu ta
sama anatomia: zakres → wybór (kółka/zakładki) → karty (tytuł, ⓘ, Reset) → akcje panelu na dole.

```
Project   nazwa projektu + [przełącz projekt] [album] [nazwa] [ustawienia]
          Photos/Video · Sound · Activity   (dodawanie + lista na stronie)
Edit      Cut       zakres (Reset = cały klip) · At the playhead · Cut inside the shot · Flags
                    [Delete clip na dole panelu]
          Speed     Constant/Variable · Sound · Direction (Forwards/Backwards)
          Steady    As shot / Steadied / Locked · Strength · Crop
          Fades     wejście / wyjście
          Frame     kształt filmu · ten klip w kadrze · Geometry · Perspective · Lens · ruch kadru
          Frame rate  frame rate filmu
          Moments   najlepsze chwile z danych zegarka
Adjust    [Layers | Keys | Applies to]  →  Mask/Adjust (przy masce) + opcje maski
          Looks     Cinematic · Harmony · Profiles · Presets
          Light     Exposure … Roll-off · Curve
          Colour    Temperature … Skin
          Presence  Texture · Clarity · Dehaze · Defringe
          Mixer     Colour mixer · Point colour · Hue curves · Channels · B&W · Calibration
          Film      Split tone · Tame · Vignette · Grading · Printer lights
          Effects   Bokeh · Grain · Glow · Halation · Mist · Anamorphic · Star · Prism · Lens · Gate weave · Bars
          Tools     LUT · Shot match · Blend in
Overlays  Text · Stickers · Route map · Dashboard · Graph   (listy dodanych w arkuszu)
Audio     dźwięk klipów · wybrany dźwięk · Level / Fade in / Fade out
Export    preset · linia „co wyjdzie” (ustawienia w arkuszu) · Export · Dashboard only
```

## 8. Co zmieniono w tym przeglądzie i dlaczego

| Było | Jest | Dlaczego |
|---|---|---|
| Adjust › Tools › Looks › przełącznik Cinematic / Profiles / My presets, a w Cinematic jeszcze Colour harmony | Adjust › **Looks** (pierwsza sekcja) › Cinematic · Harmony · Profiles · Presets, każde jedno dotknięcie od sekcji | Look to najczęstszy start (S1). Był na czwartym poziomie, pod nazwą „Tools”, z kolejnym przełącznikiem w środku. Harmony było ukryte pod Cinematic, choć to osobne narzędzie. |
| Steady i Direction w karcie Cut | **Steady** jako osobne narzędzie Edit, **Direction** w Speed | Cut odpowiada na pytanie „który fragment”. Stabilizacja to osobna operacja; kierunek odtwarzania to cecha prędkości. |
| Karta „Clip” z „Full clip” i „Delete clip” obok siebie | „Full clip” = **Reset karty Cut**; **Delete clip** jako akcja na dole panelu | Cofnięcie przycięcia to reset przycięcia. Usunięcie klipu to akcja na klipie, a stawianie jej obok resetu zaprasza do pomyłki. |
| Kształt filmu i kadr klipu w Frame, bez kolejności | **Edit › Frame**: najpierw kształt filmu (jeden dla wszystkich klipów), pod nim ten klip w kadrze | Wszystko, co dotyczy kadru, jest w Frame; karta całego filmu stoi pierwsza i mówi o tym w ⓘ. |
| My presets: cztery jednakowe kafle (None, Preset 1, Save this look, Manage) | wybór „On this clip” (None + presety), a pod linią małe akcje „+ Save this look” i „Manage…” | Wybór i akcja nie mogą wyglądać tak samo. |
| Split tone jako trzy kółka (Split, Shadow hue, Light hue) | jedno kółko **Split tone**, w karcie Amount + dwa odcienie | To jedno narzędzie; trzy kółka sugerowały trzy niezależne korekty. |

## 9. Flow krok po kroku (stan po przeglądzie)

Liczone dotknięcia od otwartego projektu z klipem (bez przewijania i bez samych zmian suwaków).

**S1. Filmowy look do Reelsa: 7 dotknięć**
1. Export › preset *Reels · TikTok · Shorts* (2) – ustawia 9:16 i 30 fps.
2. Adjust › Looks › Cinematic (3) – sekcja Looks jest pierwsza.
3. *Analyse clip* (1) → wybór looku z obwódką (1) → suwak Strength.

**S2. Stabilizacja i zwolnienie ujęcia: 5 dotknięć**
1. Edit › Steady › *Steadied* (3).
2. Edit › Speed › *0.5×* (2) – kierunek (Backwards) w tej samej karcie.

**S3. Album: jeden look na zaznaczone zdjęcia: 8 dotknięć**
1. Adjust › Looks › Presets (3) › *+ Save this look* (1) → nazwa (1).
2. Przełącz zdjęcie (1) → Presets › wybór presetu (1). Dla wielu zdjęć naraz: zaznacz je w
   albumie, potem Tools › Shot match › *Match marked* (preset jest własnością jednego zdjęcia,
   więc Applies to › All go nie rozsyła).

**S4. Przycięcie klipu i powrót do całości: 4 dotknięcia**
1. Edit › Cut (2) → *Start here* / *End here* (1).
2. Cofnięcie: *Reset* w karcie Cut (1).

**S5. Kształt filmu 4:5 dla Instagrama: 3 dotknięcia**
1. Edit › Frame (2) › *4:5* w karcie Timeline frame (1).

## 10. Do sprawdzenia w następnym przeglądzie

- **Dashboard › Sync**: dopasowanie danych zegarka dotyczy też tekstów i mapy, a nie tylko
  dashboardu. Rozważyć przeniesienie do Project › Activity.
- **Vignette** jest w Film (bo to korekta obrazu), a Lightroom trzyma ją w Effects. Zostaje,
  dopóki Effects to wyłącznie efekty nakładane na obraz.
- **Undo w Shot match i dopasowaniu grupy**: relikt sesji; globalne ↶ robi to samo.
- **Domyślna sekcja Adjust**: Looks jest pierwsza, ale Adjust otwiera się na ostatniej
  używanej korekcie (dla nowego projektu: Exposure).
