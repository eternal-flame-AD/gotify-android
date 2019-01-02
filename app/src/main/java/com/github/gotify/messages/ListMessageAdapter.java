package com.github.gotify.messages;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.RecyclerView;
import butterknife.BindView;
import butterknife.ButterKnife;
import com.github.gotify.R;
import com.github.gotify.Utils;
import com.github.gotify.client.model.Message;
import com.github.gotify.messages.provider.MessageWithImage;
import com.squareup.picasso.Picasso;
import java.util.List;

import static android.content.Context.CLIPBOARD_SERVICE;

public class ListMessageAdapter extends BaseAdapter {

    private Context content;
    private Picasso picasso;
    private List<MessageWithImage> items;
    private Delete delete;

    ListMessageAdapter(
            Context context, Picasso picasso, List<MessageWithImage> items, Delete delete) {
        super();
        this.content = context;
        this.picasso = picasso;
        this.items = items;
        this.delete = delete;
    }

    void items(List<MessageWithImage> items) {
        this.items = items;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public MessageWithImage getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).message.getId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final View view;
        if (convertView == null) {
            view = LayoutInflater.from(content).inflate(R.layout.message_item, parent, false);
        } else {
            view = convertView;
        }
        ViewHolder holder = new ViewHolder(view);
        final MessageWithImage message = items.get(position);
        holder.message.setText(message.message.getMessage());
        holder.title.setText(message.message.getTitle());
        picasso.load(message.image)
                .error(R.drawable.ic_alarm)
                .placeholder(R.drawable.ic_placeholder)
                .into(holder.image);
        holder.date.setText(
                message.message.getDate() != null
                        ? Utils.dateToRelative(message.message.getDate())
                        : "?");
        holder.copy.setOnClickListener(
                (ignored) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager)
                                    holder.copy.getContext().getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard == null) {
                        Toast.makeText(
                                        holder.copy.getContext(),
                                        R.string.clipboard_copy_failed,
                                        Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                    "message",
                                    message.message.getTitle()
                                            + "\n"
                                            + message.message.getMessage()));
                    Toast.makeText(
                                    holder.copy.getContext(),
                                    R.string.clipboard_copied,
                                    Toast.LENGTH_SHORT)
                            .show();
                    return;
                });
        holder.delete.setOnClickListener((ignored) -> delete.delete(message.message));

        return view;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        @BindView(R.id.message_image)
        ImageView image;

        @BindView(R.id.message_text)
        TextView message;

        @BindView(R.id.message_title)
        TextView title;

        @BindView(R.id.message_date)
        TextView date;

        @BindView(R.id.message_delete)
        ImageButton delete;

        @BindView(R.id.message_copy)
        ImageButton copy;

        ViewHolder(final View view) {
            super(view);
            ButterKnife.bind(this, view);
        }
    }

    public interface Delete {
        void delete(Message message);
    }
}
