package com.iitm.beacon.submission;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * One topic section bound from the submission HTML form (plain mutable
 * JavaBean — see {@link ContactFormEntry} for why). New photos come from one
 * {@code <input type="file" multiple name="sections[k].photos">}: every chosen
 * file is a part of that name, bound in order into {@code photos}, and the
 * tags of the file at index {@code j} arrive as {@code photoTags[j]} (the
 * form's script adds one tags field per file and keeps the input's file list
 * and those indices in step). An empty part — an input left untouched, which
 * a browser still posts — is skipped by the adapter ({@link FormSubmission}).
 *
 * <p>Existing photos (edit mode only) are represented separately from new
 * uploads: {@code existingPhotoUrls}/{@code existingPhotoTags} are parallel
 * lists (one hidden url + one tags text field per already-stored photo), and
 * {@code removedPhotoUrls} collects the urls of any the visitor unchecked
 * ("remove this photo") — the standard multi-checkbox-into-a-list binding
 * pattern, chosen specifically because it works correctly whether zero, some,
 * or all boxes are checked (unlike trying to bind a parallel {@code
 * List<Boolean>} by index, which breaks once a checkbox goes unchecked and
 * simply stops being submitted at all).
 */
@Getter
@Setter
@NoArgsConstructor
public class SectionFormEntry {

    private String topicSlug;
    private String answerText;
    private List<String> existingPhotoUrls = new ArrayList<>();
    private List<String> existingPhotoTags = new ArrayList<>();
    private List<String> removedPhotoUrls = new ArrayList<>();
    private List<MultipartFile> photos = new ArrayList<>();
    private List<String> photoTags = new ArrayList<>();
}
