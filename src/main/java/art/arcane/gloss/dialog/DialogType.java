package art.arcane.gloss.dialog;

import com.google.gson.annotations.SerializedName;

/** The five screens the dialog protocol renders; each one reads a different half of {@link DialogDoc}. */
public enum DialogType {
    @SerializedName("notice") NOTICE,
    @SerializedName("confirmation") CONFIRMATION,
    @SerializedName("multi_action") MULTI_ACTION,
    @SerializedName("server_links") SERVER_LINKS,
    @SerializedName("dialog_list") DIALOG_LIST
}
